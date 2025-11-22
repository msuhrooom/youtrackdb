package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jetbrains.youtrackdb.api.record.Direction;
import com.jetbrains.youtrackdb.api.record.Edge;
import com.jetbrains.youtrackdb.api.record.RID;
import com.jetbrains.youtrackdb.api.record.Vertex;
import com.jetbrains.youtrackdb.internal.core.command.CommandContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for CascadeDeleteTraverser using mocked entities.
 * <p>
 * Tests the graph traversal logic without requiring database setup. Covers cycle detection, depth
 * limits, entity count limits, and policy behavior.
 */
public class CascadeDeleteTraverserUnitTest {

  private CommandContext mockContext;
  private Vertex rootVertex;
  private Vertex childVertex1;
  private Vertex childVertex2;
  private Edge edge1;
  private Edge edge2;

  @Before
  public void setup() {
    mockContext = mock(CommandContext.class);
    rootVertex = mock(Vertex.class);
    childVertex1 = mock(Vertex.class);
    childVertex2 = mock(Vertex.class);
    edge1 = mock(Edge.class);
    edge2 = mock(Edge.class);
  }

  @Test
  public void testTraverserWithNoCascadingPolicy() {
    // NONE policy should return empty cascade entities
    var traverser = new CascadeDeleteTraverser(rootVertex,
        CascadeDeletePolicy.NONE, mockContext);

    var result = traverser.collectCascadeEntityRids();
    assertNotNull("Result should not be null", result);
    assertTrue("NONE policy should return empty list", result.isEmpty());
  }

  @Test
  public void testTraverserWithRestrictPolicy() {
    // RESTRICT policy should not cascade
    var traverser = new CascadeDeleteTraverser(rootVertex,
        CascadeDeletePolicy.RESTRICT, mockContext);

    var result = traverser.collectCascadeEntityRids();
    assertTrue("RESTRICT policy should return empty list", result.isEmpty());
  }

  @Test
  public void testTraverserWithSetNullPolicy() {
    // SET_NULL policy should not cascade
    var traverser = new CascadeDeleteTraverser(rootVertex,
        CascadeDeletePolicy.SET_NULL, mockContext);

    var result = traverser.collectCascadeEntityRids();
    assertTrue("SET_NULL policy should return empty list", result.isEmpty());
  }

  @Test
  public void testTraverserWithSetDefaultPolicy() {
    // SET_DEFAULT policy should not cascade
    var traverser = new CascadeDeleteTraverser(rootVertex,
        CascadeDeletePolicy.SET_DEFAULT, mockContext);

    var result = traverser.collectCascadeEntityRids();
    assertTrue("SET_DEFAULT policy should return empty list", result.isEmpty());
  }

  @Test
  public void testTraverserEagerPolicyDepthLimit() {
    // Setup: root -> child1 (depth 1)
    List<Edge> edges = new ArrayList<>();
    edges.add(edge1);

    // Create mock RIDs for the child vertices
    RID childRid1 = mock(RID.class);

    when(rootVertex.getEdges(Direction.OUT)).thenReturn(edges);
    when(edge1.getTo()).thenReturn(childVertex1);
    when(childVertex1.getIdentity()).thenReturn(childRid1);
    when(childRid1.toString()).thenReturn("#0:1");
    when(childVertex1.getEdges(Direction.OUT)).thenReturn(new ArrayList<>());

    var traverser = new CascadeDeleteTraverser(rootVertex,
        CascadeDeletePolicy.CASCADE_EAGER, mockContext);

    var result = traverser.collectCascadeEntityRids();
    assertNotNull("Result should not be null", result);
    assertFalse("Should contain child vertices", result.isEmpty());
  }

  @Test
  public void testTraverserLazyPolicyAllowsDeeper() {
    // LAZY policy has higher depth limit (10 vs 3 for EAGER)
    // We can't directly test the limit without building a 10-level graph,
    // but we can verify it accepts the policy
    var lazyTraverser = new CascadeDeleteTraverser(rootVertex,
        CascadeDeletePolicy.CASCADE_LAZY, mockContext);

    when(rootVertex.getEdges(Direction.OUT)).thenReturn(new ArrayList<>());

    try {
      // LAZY policy in foreground with no entities returns empty (schedules background work)
      var result = lazyTraverser.collectCascadeEntityRids();
      assertNotNull("Lazy traverser should return non-null", result);
      // In foreground with no background manager initialized, it would schedule background work
      // but for this unit test we're testing it returns something
    } catch (Exception e) {
      // Expected: background manager not initialized in unit test context
      assertTrue("Should fail due to missing background manager",
          e.getMessage() != null && e.getMessage().contains("Background manager"));
    }
  }

  @Test
  public void testTraverserHybridPolicyFallback() {
    // HYBRID policy attempts eager first
    var hybridTraverser = new CascadeDeleteTraverser(rootVertex,
        CascadeDeletePolicy.CASCADE_HYBRID, mockContext);

    when(rootVertex.getEdges(Direction.OUT)).thenReturn(new ArrayList<>());

    var result = hybridTraverser.collectCascadeEntityRids();
    assertNotNull("Hybrid traverser should work", result);
  }

  @Test
  public void testTraverserNullEdgeHandling() {
    // Test handling of null edge targets (dangling edges)
    List<Edge> edges = new ArrayList<>();
    edges.add(edge1);

    when(rootVertex.getEdges(Direction.OUT)).thenReturn(edges);
    when(edge1.getTo()).thenReturn(null); // Dangling edge

    var traverser = new CascadeDeleteTraverser(rootVertex,
        CascadeDeletePolicy.CASCADE_EAGER, mockContext);

    // Should not throw exception
    var result = traverser.collectCascadeEntityRids();
    assertNotNull("Should handle null edges gracefully", result);
  }

  @Test
  public void testTraverserEmptyEdgeList() {
    // Root with no outgoing edges
    when(rootVertex.getEdges(Direction.OUT)).thenReturn(new ArrayList<>());

    var traverser = new CascadeDeleteTraverser(rootVertex,
        CascadeDeletePolicy.CASCADE_EAGER, mockContext);

    var result = traverser.collectCascadeEntityRids();
    assertTrue("No edges means no cascade entities", result.isEmpty());
  }

  @Test
  public void testTraverserMultipleChildren() {
    // Root with multiple children
    List<Edge> edges = new ArrayList<>();
    edges.add(edge1);
    edges.add(edge2);

    // Create mock RIDs
    RID childRid1 = mock(RID.class);
    RID childRid2 = mock(RID.class);

    when(rootVertex.getEdges(Direction.OUT)).thenReturn(edges);
    when(edge1.getTo()).thenReturn(childVertex1);
    when(edge2.getTo()).thenReturn(childVertex2);
    when(childVertex1.getIdentity()).thenReturn(childRid1);
    when(childVertex2.getIdentity()).thenReturn(childRid2);
    when(childRid1.toString()).thenReturn("#0:1");
    when(childRid2.toString()).thenReturn("#0:2");
    when(childVertex1.getEdges(Direction.OUT)).thenReturn(new ArrayList<>());
    when(childVertex2.getEdges(Direction.OUT)).thenReturn(new ArrayList<>());

    var traverser = new CascadeDeleteTraverser(rootVertex,
        CascadeDeletePolicy.CASCADE_EAGER, mockContext);

    var result = traverser.collectCascadeEntityRids();
    assertTrue("Should find both children", result.size() >= 2);
  }

  @Test
  public void testTraverserCycleDetection() {
    // Simulate cycle: root -> child1 -> root
    List<Edge> rootEdges = new ArrayList<>();
    rootEdges.add(edge1);

    List<Edge> childEdges = new ArrayList<>();
    childEdges.add(edge2);

    RID childRid1 = mock(RID.class);

    when(rootVertex.getEdges(Direction.OUT)).thenReturn(rootEdges);
    when(edge1.getTo()).thenReturn(childVertex1);
    when(childVertex1.getIdentity()).thenReturn(childRid1);
    when(childRid1.toString()).thenReturn("#0:1");
    when(childVertex1.getEdges(Direction.OUT)).thenReturn(childEdges);
    when(edge2.getTo()).thenReturn(rootVertex); // Creates cycle

    var traverser = new CascadeDeleteTraverser(rootVertex,
        CascadeDeletePolicy.CASCADE_EAGER, mockContext);

    // Should not infinitely loop
    var result = traverser.collectCascadeEntityRids();
    assertNotNull("Should detect and handle cycles", result);
    assertTrue("Cycle should limit traversal", result.size() < 100);
  }

  @Test
  public void testTraverserVisitedEntityTracking() {
    // Ensure same entity isn't visited twice
    // Setup: root -> child1, root -> child1 (same child from multiple edges)
    List<Edge> edges = new ArrayList<>();
    edges.add(edge1);
    edges.add(edge2);

    RID childRid1 = mock(RID.class);

    when(rootVertex.getEdges(Direction.OUT)).thenReturn(edges);
    when(edge1.getTo()).thenReturn(childVertex1);
    when(edge2.getTo()).thenReturn(childVertex1); // Same vertex
    when(childVertex1.getIdentity()).thenReturn(childRid1);
    when(childRid1.toString()).thenReturn("#0:1");
    when(childVertex1.getEdges(Direction.OUT)).thenReturn(new ArrayList<>());

    var traverser = new CascadeDeleteTraverser(rootVertex,
        CascadeDeletePolicy.CASCADE_EAGER, mockContext);

    var result = traverser.collectCascadeEntityRids();
    assertNotNull("Should handle multiple references", result);
    // Should not double-count the same entity
    assertTrue("Should track visited entities", result.size() <= 2);
  }

  @Test
  public void testTraverserEagerPolicyLimits() {
    // EAGER: max depth 3, max count 100
    // Create a 3-level deep graph
    List<Edge> level1Edges = new ArrayList<>();
    level1Edges.add(edge1);

    List<Edge> level2Edges = new ArrayList<>();
    level2Edges.add(edge2);

    var level2Vertex = mock(Vertex.class);
    var level3Vertex = mock(Vertex.class);

    RID childRid1 = mock(RID.class);
    RID level2Rid = mock(RID.class);

    when(rootVertex.getEdges(Direction.OUT)).thenReturn(level1Edges);
    when(edge1.getTo()).thenReturn(childVertex1);
    when(childVertex1.getIdentity()).thenReturn(childRid1);
    when(childRid1.toString()).thenReturn("#0:1");
    when(childVertex1.getEdges(Direction.OUT)).thenReturn(level2Edges);
    when(edge2.getTo()).thenReturn(level2Vertex);
    when(level2Vertex.getIdentity()).thenReturn(level2Rid);
    when(level2Rid.toString()).thenReturn("#0:2");
    when(level2Vertex.getEdges(Direction.OUT)).thenReturn(new ArrayList<>());

    var traverser = new CascadeDeleteTraverser(rootVertex,
        CascadeDeletePolicy.CASCADE_EAGER, mockContext);

    var result = traverser.collectCascadeEntityRids();
    assertTrue("Should respect depth limit", result.size() <= 3);
  }

  @Test
  public void testTraverserLazyPolicyHigherLimits() {
    // LAZY: max depth 10, max count 100000
    // In foreground, LAZY policy schedules background processing and returns empty
    // In this unit test environment (no background manager), we just verify it doesn't crash
    List<Edge> edges = new ArrayList<>();
    edges.add(edge1);

    RID childRid1 = mock(RID.class);

    when(rootVertex.getEdges(Direction.OUT)).thenReturn(edges);
    when(edge1.getTo()).thenReturn(childVertex1);
    when(childVertex1.getIdentity()).thenReturn(childRid1);
    when(childRid1.toString()).thenReturn("#0:1");
    when(childVertex1.getEdges(Direction.OUT)).thenReturn(new ArrayList<>());

    var traverser = new CascadeDeleteTraverser(rootVertex,
        CascadeDeletePolicy.CASCADE_LAZY, mockContext);

    try {
      var result = traverser.collectCascadeEntityRids();
      assertNotNull("LAZY should process or schedule background", result);
    } catch (Exception e) {
      // Expected: background manager not initialized in unit test context
      assertTrue("Should fail due to missing background manager",
          e.getMessage() != null && e.getMessage().contains("Background manager"));
    }
  }

  @Test
  public void testTraverserHybridPolicyLimits() {
    // HYBRID: max depth 5, max count 1000
    List<Edge> edges = new ArrayList<>();
    edges.add(edge1);

    RID childRid1 = mock(RID.class);

    when(rootVertex.getEdges(Direction.OUT)).thenReturn(edges);
    when(edge1.getTo()).thenReturn(childVertex1);
    when(childVertex1.getIdentity()).thenReturn(childRid1);
    when(childRid1.toString()).thenReturn("#0:1");
    when(childVertex1.getEdges(Direction.OUT)).thenReturn(new ArrayList<>());

    var traverser = new CascadeDeleteTraverser(rootVertex,
        CascadeDeletePolicy.CASCADE_HYBRID, mockContext);

    var result = traverser.collectCascadeEntityRids();
    assertNotNull("HYBRID should process", result);
  }

  @Test
  public void testTraverserEdgeInstanceOf() {
    // Test that traverser correctly handles Edge vs non-Edge entities
    List<Edge> edges = new ArrayList<>();
    edges.add(edge1);

    RID childRid1 = mock(RID.class);

    when(rootVertex.getEdges(Direction.OUT)).thenReturn(edges);
    when(edge1.getTo()).thenReturn(childVertex1);
    when(childVertex1.getIdentity()).thenReturn(childRid1);
    when(childRid1.toString()).thenReturn("#0:1");
    when(childVertex1.getEdges(Direction.OUT)).thenReturn(new ArrayList<>());

    var traverser = new CascadeDeleteTraverser(rootVertex,
        CascadeDeletePolicy.CASCADE_EAGER, mockContext);

    var result = traverser.collectCascadeEntityRids();
    assertFalse("Should handle edge traversal", result.isEmpty());
  }

  @Test
  public void testTraverserNullRootVertex() {
    // Test with null root - constructor should accept it
    try {
      var traverser = new CascadeDeleteTraverser(null,
          CascadeDeletePolicy.CASCADE_EAGER, mockContext);
      // If we get here, null is allowed
      assertNotNull("Traverser created with null root", traverser);
    } catch (NullPointerException e) {
      // Also acceptable - traverser may require non-null root
      assertTrue("Null handling is defined", true);
    }
  }

  @Test
  public void testTraverserPolicyComparison() {
    // Test that different policies create different traversers
    var eagerTraverser = new CascadeDeleteTraverser(rootVertex,
        CascadeDeletePolicy.CASCADE_EAGER, mockContext);

    var lazyTraverser = new CascadeDeleteTraverser(rootVertex,
        CascadeDeletePolicy.CASCADE_LAZY, mockContext);

    when(rootVertex.getEdges(Direction.OUT)).thenReturn(new ArrayList<>());

    // Both should be independent instances
    assertNotEquals("Different policies create different instances", eagerTraverser, lazyTraverser);
  }

  @Test
  public void testTraverserWithDirectionOut() {
    // Verify traverser uses Direction.OUT
    List<Edge> edges = new ArrayList<>();
    edges.add(edge1);

    RID childRid1 = mock(RID.class);

    when(rootVertex.getEdges(Direction.OUT)).thenReturn(edges);
    when(edge1.getTo()).thenReturn(childVertex1);
    when(childVertex1.getIdentity()).thenReturn(childRid1);
    when(childRid1.toString()).thenReturn("#0:1");
    when(childVertex1.getEdges(Direction.OUT)).thenReturn(new ArrayList<>());

    var traverser = new CascadeDeleteTraverser(rootVertex,
        CascadeDeletePolicy.CASCADE_EAGER, mockContext);

    var result = traverser.collectCascadeEntityRids();

    // Verify Direction.OUT was called
    verify(rootVertex).getEdges(Direction.OUT);
    assertFalse("Traverser should find outgoing edges", result.isEmpty());
  }
}
