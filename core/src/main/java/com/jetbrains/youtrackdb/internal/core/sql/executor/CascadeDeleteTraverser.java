package com.jetbrains.youtrackdb.internal.core.sql.executor;

import com.jetbrains.youtrackdb.api.record.Direction;
import com.jetbrains.youtrackdb.api.record.Edge;
import com.jetbrains.youtrackdb.api.record.Entity;
import com.jetbrains.youtrackdb.api.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Traverses graph relationships to identify entities for cascade deletion.
 * <p>
 * Follows YouTrackDB's Traverser pattern (similar to MatchEdgeTraverser)
 * for navigating graph structures during execution.
 */
public class CascadeDeleteTraverser {

    private final Entity rootEntity;
    private final CascadeDeletePolicy policy;
  private final Set<Entity> visitedEntities;
    private final int maxDepth;
    private final long maxCount;

    public CascadeDeleteTraverser(Entity rootEntity, CascadeDeletePolicy policy, CommandContext ctx) {
        this.rootEntity = rootEntity;
        this.policy = policy;
      this.visitedEntities = new HashSet<>();
        this.maxDepth = getMaxDepthForPolicy(policy);
        this.maxCount = getMaxCountForPolicy(policy);
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
      case CASCADE_EAGER -> 100;     // Conservative for eager
      case CASCADE_LAZY -> 100000;   // Large for lazy
      case CASCADE_HYBRID -> 1000;   // Moderate for hybrid
      default -> 0;
    };
  }

  private List<Entity> collectEagerCascade(Entity entity, int depth) {
    if (depth >= maxDepth || visitedEntities.size() >= maxCount) {
      return Collections.emptyList();
    }

    if (visitedEntities.contains(entity)) {
      return Collections.emptyList(); // Avoid cycles
    }

    visitedEntities.add(entity);
    List<Entity> result = new ArrayList<>();

    if (entity instanceof Vertex vertex) {
      result.addAll(traverseVertexCascade(vertex, depth));
    } else if (entity instanceof Edge edge) {
      result.addAll(traverseEdgeCascade(edge, depth));
    }

    return result;
  }

    /**
     * Traverses the graph and collects entities for cascade deletion.
     * Returns entities in deletion order (dependencies first).
     */
    public List<Entity> collectCascadeEntities() {
        if (!policy.isCascading()) {
            return Collections.emptyList();
        }

        List<Entity> cascadeEntities = new ArrayList<>();

        if (policy == CascadeDeletePolicy.CASCADE_HYBRID) {
            // Try eager first, fall back to lazy if too large
          var eagerResult = collectEagerCascade(rootEntity, 0);
            if (eagerResult.size() <= 100) { // Configurable threshold
                return eagerResult;
            } else {
                // Schedule for lazy processing instead
                scheduleLazyCascade(rootEntity);
                return Collections.emptyList();
            }
        } else if (policy == CascadeDeletePolicy.CASCADE_EAGER) {
            return collectEagerCascade(rootEntity, 0);
        } else if (policy == CascadeDeletePolicy.CASCADE_LAZY) {
            scheduleLazyCascade(rootEntity);
            return Collections.emptyList();
        }

        return cascadeEntities;
    }

  private List<Entity> traverseVertexCascade(Vertex vertex, int depth) {
        List<Entity> result = new ArrayList<>();

    var outgoingEdges = vertex.getEdges(Direction.OUT);

    for (var edge : outgoingEdges) {
      var targetVertex = edge.getTo();

      // Safety check: ensure we have a valid target vertex
      if (targetVertex == null) {
        continue; // Skip dangling edges
      }

      if (visitedEntities.contains(targetVertex)) {
        // prevents infinite loops in circular graphs
        continue;
      }

      // prevents revisiting the same entity
      visitedEntities.add(targetVertex);

      result.add(targetVertex);

      var nestedCascadeEntities = collectEagerCascade(targetVertex, depth + 1);
      result.addAll(nestedCascadeEntities);
    }

    // Return collected entities in traversal order
    // Leaf nodes are collected first during depth-first traversal
        return result;
    }

  private List<Entity> traverseEdgeCascade(Edge edge, int depth) {
    var targetVertex = edge.getTo();
    if (targetVertex == null) {
      return Collections.emptyList(); // Dangling edge, skip
    }

    return traverseVertexCascade(targetVertex, depth);
    }

  @SuppressWarnings("EmptyMethod")
    private void scheduleLazyCascade(Entity entity) {
        // TODO: Implement lazy cascade scheduling
        // 1. Create cascade deletion job
        // 2. Add to background processing queue
        // 3. Mark entity for cascade processing

    // This would integrate with YouTrackDB's existing background
        // processing infrastructure (similar to indexing jobs)
    }
}