package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.api.query.ExecutionStep;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;

/**
 * Deletes records coming from upstream steps
 */
public class DeleteStep extends AbstractExecutionStep {

  public DeleteStep(CommandContext ctx, boolean profilingEnabled) {
    super(ctx, profilingEnabled);
  }

  @Override
  public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
    assert prev != null;
    var upstream = prev.start(ctx);
    return upstream.map(DeleteStep::mapResult);
  }

  private static Result mapResult(Result result, CommandContext ctx) {
    if (!result.isIdentifiable()) {
      throw new DatabaseException("Cannot delete non-record result: " + result);
    }

    var session = ctx.getDatabaseSession();
    var tx = session.getActiveTransaction();

    // get just the identity (RID) from the result's record
    var rid = result.asRecord().getIdentity();

    // reload the record in the *current* tx, which binds it properly
    var boundRecord = tx.load(rid);  // or session.load(rid) depending on API

    if (boundRecord != null) {
      tx.delete(boundRecord);      // now delete a record that belongs to this tx
    }

    return result;
  }


  @Override
  public String prettyPrint(int depth, int indent) {
    var spaces = ExecutionStepInternal.getIndent(depth, indent);
    var result = new StringBuilder();
    result.append(spaces);
    result.append("+ DELETE");
    if (profilingEnabled) {
      result.append(" (" + getCostFormatted() + ")");
    }
    return result.toString();
  }

  @Override
  public ExecutionStep copy(CommandContext ctx) {
    return new DeleteStep(ctx, this.profilingEnabled);
  }

  @Override
  public boolean canBeCached() {
    return true;
  }
}
