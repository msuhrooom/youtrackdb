package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.parser.SQLDeleteStatement;

/**
 * Plans cascade deletion execution based on SQL DELETE statements with CASCADE options.
 * 
 * Follows YouTrackDB's execution planning pattern where Planners create ExecutionPlans
 * that contain execution steps.
 */
public class CascadeDeleteExecutionPlanner extends DeleteExecutionPlanner {
    
    private final CascadeDeletePolicy cascadePolicy;
    
    public CascadeDeleteExecutionPlanner(SQLDeleteStatement stm, CascadeDeletePolicy cascadePolicy) {
        super(stm);
        this.cascadePolicy = cascadePolicy;
    }
    
    @Override
    public DeleteExecutionPlan createExecutionPlan(CommandContext ctx, boolean enableProfiling) {
        var executionPlan = new CascadeDeleteExecutionPlan(ctx, cascadePolicy);
        
        // Build execution plan following YouTrackDB pattern:
        // 1. First get the entities to delete (from base DeleteExecutionPlanner)
        var baseStep = super.createExecutionPlan(ctx, enableProfiling).getSteps().get(0);
        executionPlan.chain(baseStep);
        
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