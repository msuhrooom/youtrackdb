package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.exception.DatabaseException;

/**
 * Exception thrown when cascade delete operations fail.
 * <p>
 * This exception is used for both synchronous and asynchronous cascade delete failures, providing
 * context about what went wrong during the cascade operation.
 */
public class CascadeDeleteException extends DatabaseException {

  /**
   * Creates a cascade delete exception with the specified message.
   *
   * @param message descriptive error message
   */
  public CascadeDeleteException(String message) {
    super("cascade_delete", message);
  }

  /**
   * Creates a cascade delete exception with the specified message and cause.
   *
   * @param message descriptive error message
   * @param cause   underlying exception that caused this failure
   */
  public CascadeDeleteException(String message, Throwable cause) {
    super("cascade_delete", message, cause);
  }
}