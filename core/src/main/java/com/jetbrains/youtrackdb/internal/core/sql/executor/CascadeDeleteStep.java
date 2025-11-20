package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.exception.DatabaseException;
import com.jetbrains.youtrackdb.api.query.ExecutionStep;
import com.jetbrains.youtrackdb.api.query.Result;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.Vertex;
import com.jetbrains.youtrackdb.api.record.Edge;
import com.jetbrains.youtrackdb.internal.common.concur.TimeoutException;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import com.jetbrains.youtrackdb.internal.core.sql.executor.resultset.ExecutionStream;

import java.util.*;

/**
 * Executes cascade deletion based on configured cascade policies.
 * 
 * This step handles both eager and lazy cascading deletion strategies:
 * - Eager: Performs cascading deletion within the same transaction
 * - Lazy: Schedules cascading deletion for background processing
 * 
 * For YouTrackDB's use case (issue tracking with large connected graphs),
 * lazy cascading is preferred to avoid long-running transactions.
 */
public class CascadeDeleteStep extends AbstractExecutionStep {
    
    private final CascadeDeletePolicy cascadePolicy;
    private final boolean isLazyCascade;
    
    public CascadeDeleteStep(CommandContext ctx, CascadeDeletePolicy cascadePolicy, 
                           boolean profilingEnabled) {
        super(ctx, profilingEnabled);
        this.cascadePolicy = cascadePolicy;
        this.isLazyCascade = cascadePolicy.isLazy();
    }

    @Override
    public ExecutionStream internalStart(CommandContext ctx) throws TimeoutException {
        assert prev != null;
        var upstream = prev.start(ctx);
        return upstream.map(result -> processCascadeDelete(result, ctx));
    }

    private Result processCascadeDelete(Result result, CommandContext ctx) {
        if (!result.isEntity()) {
            throw new DatabaseException("Can only cascade delete entities, got: " + result);
        }
        
        Entity entity = result.asEntity();
        
        if (isLazyCascade) {
            // Lazy cascade: Schedule for background processing
            scheduleLazyCascade(entity, ctx);
        } else {
            // Eager cascade: Process immediately in current transaction
            executeEagerCascade(entity, ctx);
        }
        
        // Delete the root entity
        ctx.getDatabaseSession().delete(entity);
        
        return result;
    }
    
    /**
     * Schedules cascade deletion for background processing.
     * This is the preferred approach for YouTrackDB to handle large graphs.
     */
    private void scheduleLazyCascade(Entity entity, CommandContext ctx) {
        var traverser = new CascadeDeleteTraverser(entity, cascadePolicy, ctx);
        traverser.collectCascadeEntities(); // This will schedule lazy processing
    }
    
    /**
     * Executes cascade deletion immediately within current transaction.
     * Use with caution for large graphs.
     */
    private void executeEagerCascade(Entity entity, CommandContext ctx) {
        var traverser = new CascadeDeleteTraverser(entity, cascadePolicy, ctx);
        var cascadeEntities = traverser.collectCascadeEntities();
        
        // Delete cascade entities in the order returned by traverser
        // (dependencies first to maintain referential integrity)
        for (Entity cascadeEntity : cascadeEntities) {
            ctx.getDatabaseSession().delete(cascadeEntity);
        }
    }

    @Override
    public String prettyPrint(int depth, int indent) {
        var spaces = ExecutionStepInternal.getIndent(depth, indent);
        var result = new StringBuilder();
        result.append(spaces);
        result.append("+ CASCADE DELETE");
        result.append(" (").append(isLazyCascade ? "LAZY" : "EAGER").append(")");
        if (profilingEnabled) {
            result.append(" (").append(getCostFormatted()).append(")");
        }
        return result.toString();
    }

    @Override
    public ExecutionStep copy(CommandContext ctx) {
        return new CascadeDeleteStep(ctx, cascadePolicy, profilingEnabled);
    }

    @Override
    public boolean canBeCached() {
        return false; // Cascade deletion should not be cached
    }
}