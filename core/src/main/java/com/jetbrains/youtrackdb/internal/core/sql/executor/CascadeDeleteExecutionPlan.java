package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.DatabaseSession;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Execution plan for cascade deletions.
 * 
 * Follows YouTrackDB's pattern where ExecutionPlan extends from appropriate base class
 * and provides plan-specific behavior.
 */
public class CascadeDeleteExecutionPlan extends DeleteExecutionPlan {
    
    private final CascadeDeletePolicy cascadePolicy;
    
    public CascadeDeleteExecutionPlan(CommandContext ctx, CascadeDeletePolicy cascadePolicy) {
        super(ctx);
        this.cascadePolicy = cascadePolicy;
    }
    
    public CascadeDeletePolicy getCascadePolicy() {
        return cascadePolicy;
    }

    @Override
    public @Nonnull Result toResult(@Nullable DatabaseSession session) {
        var res = (ResultInternal) super.toResult(session);
        res.setProperty("type", "CascadeDeleteExecutionPlan");
        res.setProperty("cascadePolicy", cascadePolicy.toString());
        return res;
    }

    @Override
    public boolean canBeCached() {
        // Cascade deletes should generally not be cached due to their complexity
        // and potential for large-scale changes
        return false;
    }

    @Override
    public InternalExecutionPlan copy(CommandContext ctx) {
        var copy = new CascadeDeleteExecutionPlan(ctx, cascadePolicy);
        super.copyOn(copy, ctx);
        return copy;
    }
}