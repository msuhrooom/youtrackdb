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

    public CascadeDeleteExecutionPlanner(SQLDeleteStatement stm, CascadeDeletePolicy cascadePolicy) {
      this.stm = stm;
        this.cascadePolicy = cascadePolicy;
    }

  public DeleteExecutionPlan createExecutionPlan(CommandContext ctx, boolean enableProfiling) {
        var executionPlan = new CascadeDeleteExecutionPlan(ctx, cascadePolicy);

    // Build execution plan following YouTrackDB pattern:
    // 1. First get the entities to delete (use non-cascade delete planner)
    // Create a copy with cascade=false to avoid infinite recursion
    var stmCopy = stm.copy();
    stmCopy.setCascade(false);
    var basePlan = new DeleteExecutionPlanner(stmCopy).createExecutionPlan(ctx, enableProfiling);
    var baseStep = basePlan.getSteps().get(0);
      executionPlan.chain((ExecutionStepInternal) baseStep);

    // 2. Add cascade delete step if cascade is enabled
        if (cascadePolicy != CascadeDeletePolicy.NONE) {
            var cascadeStep = new CascadeDeleteStep(ctx, cascadePolicy, enableProfiling);
            executionPlan.chain(cascadeStep);
        }

    // 3. Add final delete step
        var deleteStep = new DeleteStep(ctx, enableProfiling);
        executionPlan.chain(deleteStep);

    return executionPlan;
    }
}
