package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.common.SessionPool;
import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.command.BasicCommandContext;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.SessionPoolImpl;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Manages background sessions for cascade delete operations.
 * <p>
 * Provides thread-safe session pooling and async execution capabilities for large cascade delete
 * operations that should not block the main transaction.
 */
public class CascadeDeleteBackgroundManager {

  private static final LogManager logger = LogManager.instance();
  // Configuration
  private static final int DEFAULT_POOL_SIZE = 5;
  private static final int DEFAULT_THREAD_POOL_SIZE = 3;
  private static final int BATCH_SIZE = 100;
  private static final long SHUTDOWN_TIMEOUT_SECONDS = 30;
  private final SessionPool<DatabaseSession> backgroundSessionPool;
  private final ExecutorService executorService;
  private final String databaseName;
  private final AtomicLong taskCounter = new AtomicLong(0);      // how many tasks scheduled
  private final AtomicLong threadCounter = new AtomicLong(0);    // just for thread names

  public CascadeDeleteBackgroundManager(YouTrackDB youTrackDB, String databaseName) {
    this(youTrackDB, databaseName, DEFAULT_POOL_SIZE, DEFAULT_THREAD_POOL_SIZE);
  }

  public CascadeDeleteBackgroundManager(YouTrackDB youTrackDB, String databaseName,
      int poolSize, int threadPoolSize) {
    this.databaseName = databaseName;

    // Create dedicated session pool for background cascade operations
    // Use the YouTrackDB's cached pool functionality which handles the internal types properly
    if (youTrackDB instanceof com.jetbrains.youtrackdb.internal.core.db.YouTrackDBAbstract) {
      @SuppressWarnings("unchecked")
      var youTrackDBAbstract = (com.jetbrains.youtrackdb.internal.core.db.YouTrackDBAbstract<?, DatabaseSession>) youTrackDB;
      this.backgroundSessionPool = youTrackDBAbstract.cachedPool(databaseName, "admin", "admin");
    } else {
      // Fallback: create from URL (for embedded databases)
      String url = "memory:" + databaseName;  // Assume memory database for simplicity
      this.backgroundSessionPool = new SessionPoolImpl<DatabaseSession>(
          url,
          "admin",              // Use admin user for cascade operations
          "admin"               // Default admin password
      );
    }

    // Create thread pool for async execution with meaningful names
    this.executorService = Executors.newFixedThreadPool(
        threadPoolSize,
        r -> {
          Thread t = new Thread(r, "cascade-delete-worker-" + threadCounter.incrementAndGet());
          t.setDaemon(true);
          t.setUncaughtExceptionHandler((thread, ex) ->
              logger.error(this,
                  "Uncaught exception in cascade delete thread: " + thread.getName(), ex));
          return t;
        }
    );

    logger.info(this, "Initialized cascade delete background manager for database: {} " +
            "with pool size: {} and thread pool size: {}",
        databaseName, poolSize, threadPoolSize);
  }

  /**
   * Schedules a cascade delete operation to run in the background.
   *
   * @param rootEntityRid The RID of the root entity to cascade from
   * @param cascadePolicy The cascade policy to apply
   * @return CompletableFuture that completes when cascade is done
   */
  public CompletableFuture<CascadeDeleteResult> scheduleCascadeDelete(
      RID rootEntityRid, CascadeDeletePolicy cascadePolicy) {

    // Check if background manager is shutting down
    if (executorService.isShutdown()) {
      logger.warn(this,
          "Cannot schedule cascade delete for entity {} - background manager is shutting down",
          rootEntityRid);
      // Return a completed future with failure result
      var failureResult = new CascadeDeleteResult(0, 0, false);
      return CompletableFuture.completedFuture(failureResult);
    }

    long taskId = taskCounter.incrementAndGet();

    logger.info(this, "Scheduling background cascade delete task {} for entity: {} with policy: {}",
        taskId, rootEntityRid, cascadePolicy);

    CompletableFuture<CascadeDeleteResult> future;
    try {
      future = CompletableFuture.supplyAsync(() -> {
        return executeBackgroundCascade(taskId, rootEntityRid, cascadePolicy);
      }, executorService);
    } catch (RejectedExecutionException e) {
      // Handle race condition where executor shuts down between isShutdown() check and task submission
      logger.warn(this, "Cascade delete task {} rejected - executor shutting down for entity: {}",
          taskId, rootEntityRid);
      var failureResult = new CascadeDeleteResult(0, 0, false);
      return CompletableFuture.completedFuture(failureResult);
    }

    return future.whenComplete((result, throwable) -> {
      if (throwable != null) {
        logger.error(this, "Background cascade delete task {} failed for entity: {}",
            throwable, taskId, rootEntityRid);
      } else {
        logger.info(this, "Background cascade delete task {} completed successfully. " +
                "Deleted {} entities in {} ms",
            taskId, result.deletedCount(), result.durationMs());
      }
    });
  }

  /**
   * Executes the cascade delete operation in a background session.
   */
  private CascadeDeleteResult executeBackgroundCascade(long taskId, RID rootEntityRid,
      CascadeDeletePolicy cascadePolicy) {
    long startTime = System.currentTimeMillis();
    DatabaseSession backgroundSession = null;
    int deletedCount = 0;

    try {
      // Acquire session from pool
      backgroundSession = backgroundSessionPool.acquire();
      logger.info(this, "Task {}: Acquired background session for cascade delete", taskId);

      // Ensure session is activated on current thread
      // The pool should do this automatically, but we ensure it for safety
      if (backgroundSession instanceof com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal) {
        ((com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionInternal) backgroundSession).activateOnCurrentThread();
      }

      // Create command context for background session
      CommandContext backgroundContext = createBackgroundCommandContext(backgroundSession, taskId);

      // Load the root entity and collect cascade entities in the same transaction
      List<String> cascadeEntityRids;
      backgroundSession.begin();
      try {
        EntityImpl rootEntity = backgroundSession.getActiveTransaction()
            .load(rootEntityRid);
        if (rootEntity == null) {
          throw new DatabaseException(databaseName,
              "Root entity not found for cascade delete: " + rootEntityRid);
        }

        // Create traverser and collect entities while transaction is active
        var backgroundTraverser = new CascadeDeleteTraverser(
            rootEntity,
            cascadePolicy,
            backgroundContext
        );

        logger.info(this, "Task {}: Collecting cascade entities from root: {}", taskId,
            rootEntityRid);
        cascadeEntityRids = backgroundTraverser.collectCascadeEntityRids();

        // Commit after collection is complete
        backgroundSession.getActiveTransaction().commit();
      } catch (Exception e) {
        if (backgroundSession.isTxActive()) {
          backgroundSession.getActiveTransaction().rollback();
        }
        throw e;
      }

      // Delete cascade entities in batches to avoid large transactions
      deletedCount = deleteCascadeEntitiesInBatches(taskId, cascadeEntityRids,
                                                    backgroundSession);

      // Delete root entity in separate transaction
      backgroundSession.begin();
      try {
        try {
          // Reload root entity in current transaction to get latest version
          EntityImpl reloadedRoot = backgroundSession.getActiveTransaction()
              .load(rootEntityRid);
          if (reloadedRoot != null) {
            backgroundSession.getActiveTransaction().delete(reloadedRoot);
            deletedCount++; // Count root entity
            logger.info(this, "Task {}: Deleted root entity: {}", taskId, rootEntityRid);
          } else {
            logger.warn(this, "Task {}: Root entity {} no longer exists", taskId, rootEntityRid);
          }
        } catch (com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException e) {
          // Root entity was modified by another transaction
          logger.warn(this, "Task {}: Root entity {} was modified concurrently, skipping: {}",
              taskId, rootEntityRid, e.getMessage());
        }
        backgroundSession.getActiveTransaction().commit();
      } catch (Exception e) {
        if (backgroundSession.isTxActive()) {
          backgroundSession.getActiveTransaction().rollback();
        }
        throw e;
      }

      long duration = System.currentTimeMillis() - startTime;
      return new CascadeDeleteResult(deletedCount, duration, true);

    } catch (Exception e) {
      logger.error(this, "Task {}: Background cascade execution failed for entity: {}",
          e, taskId, rootEntityRid);

      // Attempt rollback if session is in transaction
      if (backgroundSession != null && backgroundSession.isTxActive()) {
        try {
          backgroundSession.getActiveTransaction().rollback();
        } catch (Exception rollbackError) {
          logger.error(this, "Task {}: Failed to rollback background cascade transaction",
              rollbackError, taskId);
        }
      }

      throw new CascadeDeleteException("Background cascade execution failed for task: " + taskId,
          e);

    } finally {
      // Always return session to pool
      if (backgroundSession != null) {
        try {
          backgroundSession.close();  // Returns to pool
          logger.info(this, "Task {}: Returned session to pool", taskId);
        } catch (Exception e) {
          logger.error(this, "Task {}: Failed to return session to pool", e, taskId);
        }
      }
    }
  }

  /**
   * Deletes cascade entities in batches to avoid large transactions and potential timeouts.
   */
  private int deleteCascadeEntitiesInBatches(long taskId,
                                             List<String> cascadeEntityRids,
                                             DatabaseSession session)
      throws Exception {
    int totalDeleted = 0;
    int batchCount = (cascadeEntityRids.size() + BATCH_SIZE - 1) / BATCH_SIZE;

    logger.info(
        this, "Task {}: Deleting {} cascade entities in {} batches of size {}",
        taskId, cascadeEntityRids.size(), batchCount, BATCH_SIZE);

    for (int batchIndex = 0; batchIndex < batchCount; batchIndex++) {
      int start = batchIndex * BATCH_SIZE;
      int end = Math.min(start + BATCH_SIZE, cascadeEntityRids.size());
      List<String> batch = cascadeEntityRids.subList(start, end);

      session.begin();
      try {
        int deletedInBatch = 0;
        for (String ridStr : batch) {
          try {
            RID rid = RID.of(ridStr);
            // Reload entity in current session before deleting to get latest version
            EntityImpl reloadedEntity =
                session.getActiveTransaction().load(rid);
            if (reloadedEntity != null) {
              session.getActiveTransaction().delete(reloadedEntity);
              deletedInBatch++;
            } else {
              logger.warn(this, "Task {}: Entity {} no longer exists, skipping",
                          taskId, ridStr);
            }
          } catch (com.jetbrains.youtrackdb.api.exception.ConcurrentModificationException e) {
            // Entity was modified by another transaction, skip it gracefully
            logger.warn(
                this,
                "Task {}: Entity {} was modified concurrently, skipping: {}",
                taskId, ridStr, e.getMessage());
          }
        }
        session.getActiveTransaction().commit();
        totalDeleted += deletedInBatch;

        logger.info(this, "Task {}: Completed batch {}/{}, deleted {} entities",
            taskId, batchIndex + 1, batchCount, deletedInBatch);

      } catch (Exception e) {
        if (session.isTxActive()) {
          session.getActiveTransaction().rollback();
        }
        logger.error(this, "Task {}: Failed to delete batch {}/{}", e, taskId, batchIndex + 1,
            batchCount);
        throw new CascadeDeleteException("Failed to delete cascade batch " + (batchIndex + 1), e);
      }
    }

    return totalDeleted;
  }

  /**
   * Creates a command context for background cascade operations.
   */
  private CommandContext createBackgroundCommandContext(DatabaseSession backgroundSession,
      long taskId) {
    var context = new BasicCommandContext();
    // Cast to internal type since CommandContext expects DatabaseSessionEmbedded
    context.setDatabaseSession(
        (com.jetbrains.youtrackdb.internal.core.db.DatabaseSessionEmbedded) backgroundSession);

    // Set background-specific properties
    var params = new HashMap<Object, Object>();
    params.put("cascade_mode", "background");
    params.put("cascade_task_id", taskId);
    params.put("cascade_session_id", backgroundSession.toString());
    params.put("cascade_database", databaseName);
    context.setInputParameters(params);

    return context;
  }

  /**
   * Shuts down the background manager gracefully.
   */
  public void shutdown() {
    logger.info(this, "Shutting down cascade delete background manager for database: {}",
        databaseName);

    executorService.shutdown();
    try {
      if (!executorService.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        logger.warn(this,
            "Background executor did not terminate within {} seconds, forcing shutdown",
            SHUTDOWN_TIMEOUT_SECONDS);
        executorService.shutdownNow();
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      executorService.shutdownNow();
    }

    try {
      backgroundSessionPool.close();
      logger.info(this, "Closed background session pool for database: {}", databaseName);
    } catch (Exception e) {
      logger.error(this, "Failed to close background session pool for database: {}", e,
          databaseName);
    }
  }

  /**
   * Gets the current number of active background tasks.
   */
  public boolean isShutdown() {
    return executorService.isShutdown();
  }

  /**
   * Gets statistics about the background manager.
   */
  public BackgroundManagerStats getStats() {
    return new BackgroundManagerStats(
        taskCounter.get(),
        !executorService.isShutdown(),
        !backgroundSessionPool.isClosed()
    );
  }
}
