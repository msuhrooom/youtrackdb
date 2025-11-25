package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.YouTrackDB;
import com.jetbrains.youtrackdb.api.record.Direction;
import com.jetbrains.youtrackdb.api.record.Edge;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.Vertex;
import com.jetbrains.youtrackdb.internal.common.log.LogManager;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBImpl;
import com.jetbrains.youtrackdb.internal.core.db.YouTrackDBInternal;
import com.jetbrains.youtrackdb.internal.core.record.impl.EntityImpl;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Traverses graph relationships to identify entities for cascade deletion.
 * <p>
 * Follows YouTrackDB's Traverser pattern (similar to MatchEdgeTraverser)
 * for navigating graph structures during execution.
 */
public class CascadeDeleteTraverser {

  private static final LogManager logger = LogManager.instance();
  // Background manager for lazy cascade operations
  private static CascadeDeleteBackgroundManager backgroundManager;
  private final Entity rootEntity;
  private final Set<Entity> visitedEntities;
  private final CascadeDeletePolicy policy;
  private final CommandContext ctx;
  private final int maxDepth;
  private final long maxCount;

  public CascadeDeleteTraverser(Entity rootEntity,
      CascadeDeletePolicy policy,
      CommandContext ctx) {
    this.rootEntity = rootEntity;
    this.policy = policy;
    this.ctx = ctx;
    this.visitedEntities = new HashSet<>();
    this.maxDepth = getMaxDepthForPolicy(policy);
    this.maxCount = getMaxCountForPolicy(policy);
  }

  /**
   * Initializes the background manager for lazy cascade operations.
   */
  public static void initializeBackgroundManager(YouTrackDB youTrackDB, String databaseName) {
    if (backgroundManager == null) {
      backgroundManager = new CascadeDeleteBackgroundManager(youTrackDB, databaseName);
      logger.info(CascadeDeleteTraverser.class,
          "Initialized background manager for database: {}", databaseName);
    }
  }

  /**
   * Shuts down the background manager.
   */
  public static void shutdownBackgroundManager() {
    if (backgroundManager != null) {
      backgroundManager.shutdown();
      backgroundManager = null;
      logger.info(CascadeDeleteTraverser.class, "Shutdown background manager");
    }
  }

  private static int getMaxDepthForPolicy(CascadeDeletePolicy policy) {
    return switch (policy) {
      case CASCADE_EAGER -> 3;     // Conservative for eager
      case CASCADE_LAZY -> 10;     // More generous for lazy
      case CASCADE_HYBRID -> 5;    // Moderate for hybrid
      default -> 1;
    };
  }

  private static long getMaxCountForPolicy(CascadeDeletePolicy policy) {
    return switch (policy) {
      case CASCADE_EAGER -> 100;      // Conservative for eager
      case CASCADE_LAZY -> 100000;    // Large for lazy
      case CASCADE_HYBRID -> 1000;    // Moderate for hybrid
      default -> 0;
    };
  }

  /**
   * Detect whether we are already running in a background cascade context.
   */
  private boolean isBackgroundContext() {
    if (ctx == null) {
      return false;
    }
    var params = ctx.getInputParameters();
    if (params == null) {
      return false;
    }
    Object mode = params.get("cascade_mode");
    return "background".equals(mode);
  }

  private List<String> collectEagerCascadeRids(Entity entity, int depth) {
    if (depth >= maxDepth || visitedEntities.size() >= maxCount) {
      return Collections.emptyList();
    }

    if (visitedEntities.contains(entity)) {
      return Collections.emptyList(); // Avoid cycles
    }

    visitedEntities.add(entity);
    List<String> result = new ArrayList<>();

    if (entity instanceof Vertex vertex) {
      result.addAll(traverseVertexCascadeRids(vertex, depth));
    } else if (entity instanceof Edge edge) {
      result.addAll(traverseEdgeCascadeRids(edge, depth));
    }

    return result;
  }

  @Deprecated
  private List<Entity> collectEagerCascade(Entity entity, int depth) {
    return Collections.emptyList();
  }

  /**
   * Traverses the graph and collects entity RIDs for cascade deletion. Returns RIDs in deletion
   * order (dependencies first). Using RIDs instead of entity references avoids session binding
   * issues.
   */
  public List<String> collectCascadeEntityRids() {
    if (!policy.isCascading()) {
      logger.info(this, "Policy {} is not cascading, returning empty list", policy);
      return Collections.emptyList();
    }

    boolean inBackground = isBackgroundContext();

    logger.info(this,
        "Starting cascade collection with policy: {} for root entity: {} (background={})",
        policy, rootEntity.getIdentity(), inBackground);

    // HYBRID: eager if small, otherwise lazy in foreground, eager in background
    if (policy == CascadeDeletePolicy.CASCADE_HYBRID) {
      if (inBackground) {
        // In background, just do a bounded eager traversal
        return collectEagerCascadeRids(rootEntity, 0);
      }

      // Foreground: try eager first, fall back to lazy if too large
      var eagerResult = collectEagerCascadeRids(rootEntity, 0);
      if (eagerResult.size() <= 100) { // Configurable threshold
        logger.info(this,
            "HYBRID cascade using eager path, {} entities to delete", eagerResult.size());
        return eagerResult;
      } else {
        logger.info(this,
            "HYBRID cascade too large ({} entities), scheduling lazy background cascade",
            eagerResult.size());
        scheduleLazyCascade(rootEntity);
        return Collections.emptyList();
      }
    }

    // EAGER: always traverse synchronously
    if (policy == CascadeDeletePolicy.CASCADE_EAGER) {
      var result = collectEagerCascadeRids(rootEntity, 0);
      logger.info(this, "EAGER cascade collected {} entities", result.size());
      return result;
    }

    // LAZY: foreground -> schedule background; background -> behave like eager
    if (policy == CascadeDeletePolicy.CASCADE_LAZY) {
      if (inBackground) {
        logger.info(this,
            "LAZY cascade running in background context, performing eager traversal");
        var result = collectEagerCascadeRids(rootEntity, 0);
        logger.info(this,
            "Background LAZY cascade collected {} entities for deletion", result.size());
        return result;
      } else {
        logger.info(this,
            "LAZY cascade in foreground, scheduling background cascade delete");
        scheduleLazyCascade(rootEntity);
        return Collections.emptyList();
      }
    }

    // Other policies (RESTRICT, SET_NULL, etc.) are not handled here
    logger.info(this,
        "Policy {} does not perform traversal in CascadeDeleteTraverser, returning empty list",
        policy);
    return Collections.emptyList();
  }

  /**
   * Legacy method - kept for compatibility. Returns entity objects.
   */
  @Deprecated
  public List<Entity> collectCascadeEntities() {
    return Collections.emptyList();
  }

  private List<String> traverseVertexCascadeRids(Vertex vertex, int depth) {
    List<String> result = new ArrayList<>();

    var outgoingEdges = vertex.getEdges(Direction.OUT);
    logger.info(this,
        "Traversing vertex {} at depth {}, found {} outgoing edges",
        vertex.getIdentity(), depth, getEdgeCount(outgoingEdges));

    for (var edge : outgoingEdges) {
      var targetVertex = edge.getTo();
      var fromId = edge.getFrom() != null ? edge.getFrom().getIdentity() : "unknown";
      logger.info(this, "  Edge: {} -> {} (label: {})",
          fromId,
          targetVertex != null ? targetVertex.getIdentity() : "null",
          edge.getSchemaClassName());

      // Safety check: ensure we have a valid target vertex
      if (targetVertex == null) {
        logger.warn(this, "  Skipping dangling edge with null target");
        continue; // Skip dangling edges
      }

      if (visitedEntities.contains(targetVertex)) {
        // prevents infinite loops in circular graphs
        logger.info(this,
            "  Skipping already visited vertex: {}", targetVertex.getIdentity());
        continue;
      }

      // prevents revisiting the same entity
      visitedEntities.add(targetVertex);

      // Store RID instead of entity reference
      result.add(targetVertex.getIdentity().toString());

      var nestedCascadeEntities = collectEagerCascadeRids(targetVertex, depth + 1);
      result.addAll(nestedCascadeEntities);
    }

    // Return collected RIDs in traversal order
    // Leaf nodes are collected first during depth-first traversal
    return result;
  }

  private List<String> traverseEdgeCascadeRids(Edge edge, int depth) {
    var targetVertex = edge.getTo();
    if (targetVertex == null) {
      return Collections.emptyList(); // Dangling edge, skip
    }

    return traverseVertexCascadeRids(targetVertex, depth);
  }

  @Deprecated
  private List<Entity> traverseVertexCascade(Vertex vertex, int depth) {
    return Collections.emptyList();
  }

  @Deprecated
  private List<Entity> traverseEdgeCascade(Edge edge, int depth) {
    return Collections.emptyList();
  }

  private void scheduleLazyCascade(Entity entity) {
    // Never schedule from background – avoids infinite / nested scheduling
    if (isBackgroundContext()) {
      logger.warn(this,
          "scheduleLazyCascade called in background context for {}, skipping reschedule",
          entity.getIdentity());
      return;
    }

    if (backgroundManager == null) {
      synchronized (CascadeDeleteTraverser.class) {
        if (backgroundManager == null && ctx != null &&
            ctx.getDatabaseSession() != null) {
          var session = ctx.getDatabaseSession();
          var shared = session.getSharedContext();
          if (shared != null && shared.getYouTrackDB() != null) {
            YouTrackDB youTrackDB = null;
            // Prefer an existing API instance if available
            if (shared.getYouTrackDB() instanceof YouTrackDB apiInstance) {
              youTrackDB = apiInstance;
            } else if (shared.getYouTrackDB() instanceof
                       YouTrackDBInternal<?> internal) {
              @SuppressWarnings("unchecked")
              var casted = (YouTrackDBInternal<DatabaseSession>)internal;
              youTrackDB = new YouTrackDBImpl(casted);
            }
            if (youTrackDB != null) {
              initializeBackgroundManager(youTrackDB,
                                          session.getDatabaseName());
            }
          }
        }
      }
    }

    if (backgroundManager == null) {
      logger.warn(this,
                  "Background manager not available for lazy cascade " +
                  "operations; skipping cascade for {}",
                  entity.getIdentity());
      return;
    }

    // Check if background manager is shutting down
    if (backgroundManager.isShutdown()) {
      logger.warn(this,
          "Background manager is shutting down, skipping cascade delete for entity: {}",
          entity.getIdentity());
      return; // Gracefully skip the operation
    }

    var entityImpl = (EntityImpl) entity;

    // Schedule background cascade delete
    CompletableFuture<CascadeDeleteResult> cascadeTask =
        backgroundManager.scheduleCascadeDelete(entityImpl.getIdentity(), policy);

    // Add completion handling for monitoring and error reporting
    cascadeTask.whenComplete((result, throwable) -> {
      if (throwable != null) {
        logger.error(this,
            "Lazy cascade delete failed for entity: {}", throwable, entityImpl.getIdentity());
      } else {
        logger.info(this,
            "Lazy cascade delete completed for entity: {}. Result: {}",
            entityImpl.getIdentity(), result);
      }
    });

    // Mark entity for lazy processing (for tracking/monitoring)
    markEntityForLazyCascade(entityImpl);
  }

  /**
   * Marks an entity for lazy cascade processing. This can be used for tracking and monitoring
   * purposes.
   */
  private void markEntityForLazyCascade(EntityImpl entity) {
    // TODO: Implement lazy cascade tracking if needed
    logger.info(this,
        "Marked entity {} for lazy cascade processing", entity.getIdentity());
  }

  private int getEdgeCount(Iterable<Edge> edges) {
    int count = 0;
    for (Edge ignored : edges) {
      count++;
    }
    return count;
  }
}
