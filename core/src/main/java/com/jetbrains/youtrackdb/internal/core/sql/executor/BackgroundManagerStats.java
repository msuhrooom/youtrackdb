package com.jetbrains.youtrackdb.internal.core.sql.executor;

/**
 * Statistics and status information for the background cascade delete manager.
 * <p>
 * Provides monitoring information about the background processing system including task counts and
 * component status.
 */
public record BackgroundManagerStats(long totalTasksSubmitted, boolean executorActive,
                                     boolean sessionPoolActive) {

  /**
   * Gets the total number of background cascade tasks submitted since manager creation.
   *
   * @return total task count
   */
  @Override
  public long totalTasksSubmitted() {
    return totalTasksSubmitted;
  }

  /**
   * Gets whether the background executor is active and accepting new tasks.
   *
   * @return true if executor is active, false if shut down
   */
  @Override
  public boolean executorActive() {
    return executorActive;
  }

  /**
   * Gets whether the session pool is active and available for use.
   *
   * @return true if session pool is active, false if closed
   */
  @Override
  public boolean sessionPoolActive() {
    return sessionPoolActive;
  }

  /**
   * Gets whether the background manager is fully operational.
   *
   * @return true if both executor and session pool are active
   */
  public boolean isFullyOperational() {
    return executorActive && sessionPoolActive;
  }

  @Override
  public String toString() {
    return String.format(
        "BackgroundManagerStats{tasksSubmitted=%d, executorActive=%s, poolActive=%s}",
        totalTasksSubmitted, executorActive, sessionPoolActive);
  }
}