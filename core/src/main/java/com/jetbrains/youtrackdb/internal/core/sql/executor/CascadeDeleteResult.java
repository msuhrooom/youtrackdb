package com.jetbrains.youtrackdb.internal.core.sql.executor;

/**
 * Result of a cascade delete operation.
 * <p>
 * Contains information about the number of entities deleted, execution duration, and success status
 * of the cascade delete operation.
 */
public record CascadeDeleteResult(int deletedCount, long durationMs, boolean success) {

  /**
   * Gets the total number of entities deleted in the cascade operation.
   *
   * @return number of deleted entities including the root entity
   */
  @Override
  public int deletedCount() {
    return deletedCount;
  }

  /**
   * Gets the duration of the cascade delete operation in milliseconds.
   *
   * @return execution duration in milliseconds
   */
  @Override
  public long durationMs() {
    return durationMs;
  }

  /**
   * Gets whether the cascade delete operation completed successfully.
   *
   * @return true if successful, false if failed
   */
  @Override
  public boolean success() {
    return success;
  }

  @Override
  public String toString() {
    return String.format("CascadeDeleteResult{deleted=%d, duration=%dms, success=%s}",
        deletedCount, durationMs, success);
  }
}