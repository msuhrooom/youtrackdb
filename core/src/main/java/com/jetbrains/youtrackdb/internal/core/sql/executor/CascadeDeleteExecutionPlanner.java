package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLDeleteStatement;

/**
 * Plans cascade deletion execution based on SQL DELETE statements with CASCADE options.
 *
 * Follows YouTrackDB's execution planning pattern where Planners create ExecutionPlans
 * that contain execution steps.
 */
public class CascadeDeleteExecutionPlanner {

  private final SQLDeleteStatement stm;
  private final CascadeDeletePolicy cascadePolicy;

  public CascadeDeleteExecutionPlanner(SQLDeleteStatement stm,
                                       CascadeDeletePolicy cascadePolicy) {
    this.stm = stm;
    this.cascadePolicy = cascadePolicy;
  }

  public DeleteExecutionPlan createExecutionPlan(CommandContext ctx, boolean enableProfiling) {
    var executionPlan = new CascadeDeleteExecutionPlan(ctx, cascadePolicy);

    // Build a base delete plan (without cascade) and reuse its steps, inserting
    // cascade handling just before the actual DeleteStep so we preserve safety
    // checks, limits, and return/count semantics.
    var stmCopy = stm.copy();
    stmCopy.setCascade(false);
    var basePlan = new DeleteExecutionPlanner(stmCopy).createExecutionPlan(
        ctx, enableProfiling);

    boolean cascadeInserted = false;
    for (var step : basePlan.getSteps()) {
      var internalStep = (ExecutionStepInternal)step;

      // In cascade mode we deliberately skip the safe-delete guard: cascades
      // are expected to delete graph elements (vertices/edges), so blocking on
      // the vertex/edge check would prevent the cascade from running.
      if (internalStep instanceof CheckSafeDeleteStep) {
        continue;
      }

      if (!cascadeInserted && internalStep instanceof DeleteStep &&
          cascadePolicy.isCascading()) {
        executionPlan.chain(
            new CascadeDeleteStep(ctx, cascadePolicy, enableProfiling));
        cascadeInserted = true;
      }

      executionPlan.chain((ExecutionStepInternal)internalStep.copy(ctx));
    }

    // Safety net: if the base plan unexpectedly had no DeleteStep, still append
    // cascade + delete
    if (!cascadeInserted && cascadePolicy.isCascading()) {
      executionPlan.chain(
          new CascadeDeleteStep(ctx, cascadePolicy, enableProfiling));
      executionPlan.chain(new DeleteStep(ctx, enableProfiling));
    }

    return executionPlan;
  }
}
