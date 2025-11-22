package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.internal.core.id.RecordId;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Test cases for background cascade delete session management.
 * <p>
 * Tests the session pool, background execution, and error handling of the cascade delete background
 * manager.
 */
public class CascadeDeleteBackgroundManagerTest extends DbTestBase {

  private CascadeDeleteBackgroundManager backgroundManager;

  @Before
  public void setUp() throws Exception {
    // Clean up any existing static background manager first to avoid interference
    CascadeDeleteTraverser.shutdownBackgroundManager();

    // Initialize background manager for testing using inherited youTrackDB and databaseName
    backgroundManager = new CascadeDeleteBackgroundManager(
        youTrackDB,
        databaseName,
        2,  // Small pool size for testing
        2   // Small thread pool for testing
    );

    // Note: We're NOT initializing the static CascadeDeleteTraverser background manager
    // This test is specifically testing the CascadeDeleteBackgroundManager directly
  }

  @After
  public void tearDown() throws Exception {
    if (backgroundManager != null) {
      backgroundManager.shutdown();
    }
    // Clean up the static background manager
    CascadeDeleteTraverser.shutdownBackgroundManager();
  }

  @Test
  public void testBackgroundManagerInitialization() {
    // Test that background manager initializes properly
    assertNotNull("Background manager should not be null", backgroundManager);
    assertFalse("Background manager should not be shutdown initially",
        backgroundManager.isShutdown());

    // Test stats
    var stats = backgroundManager.getStats();
    assertNotNull("Stats should not be null", stats);
    assertTrue("Session pool should be active", stats.sessionPoolActive());
    assertTrue("Executor should be active", stats.executorActive());
    assertEquals("No tasks should be submitted initially", 0, stats.totalTasksSubmitted());
  }

  @Test
  public void testBackgroundManagerShutdown() {
    // Test graceful shutdown
    backgroundManager.shutdown();

    assertTrue("Background manager should be shutdown", backgroundManager.isShutdown());

    var stats = backgroundManager.getStats();
    assertFalse("Executor should be inactive after shutdown", stats.executorActive());
  }

  @Test
  public void testScheduleCascadeDelete() throws Exception {
    // Create schema first
    session.getMetadata().getSchema().createVertexClass("Project");

    // Create test data
    session.begin();
    var result = session.execute("CREATE VERTEX Project SET name = 'TestProject'");
    var projectRid = result.next().getIdentity();
    result.close();
    session.commit();

    assertNotNull("Project should be created", projectRid);

    var future = backgroundManager.scheduleCascadeDelete(
        projectRid,
        CascadeDeletePolicy.CASCADE_EAGER
    );

    assertNotNull("Future should not be null", future);

    // Wait for completion (with timeout)
    var cascadeResult = future.get(10, java.util.concurrent.TimeUnit.SECONDS);

    assertNotNull("Result should not be null", cascadeResult);
    assertTrue("Result should indicate success", cascadeResult.success());
    assertTrue("Should delete at least 1 entity", cascadeResult.deletedCount() >= 1);
    assertTrue("Duration should be positive", cascadeResult.durationMs() > 0);

    // Verify stats updated
    var stats = backgroundManager.getStats();
    assertEquals("Should have submitted 1 task", 1, stats.totalTasksSubmitted());
  }

  @Test
  public void testCascadeDeleteWithInvalidEntity() throws Exception {
    // Test error handling with non-existent entity
    // Create a RID that doesn't exist
    var invalidRid = new RecordId(1, 99999);

    var future = backgroundManager.scheduleCascadeDelete(
        invalidRid,
        CascadeDeletePolicy.CASCADE_EAGER
    );

    // Should complete with exception
    try {
      future.get(5, java.util.concurrent.TimeUnit.SECONDS);
      fail("Expected exception for invalid entity");
    } catch (Exception e) {
      // Expected - cascade should fail for non-existent entity
      assertTrue("Should be CascadeDeleteException or related exception",
          e.getCause() instanceof CascadeDeleteException ||
              e.getCause() instanceof com.jetbrains.youtrackdb.api.exception.DatabaseException);
    }
  }

  @Test
  public void testMultipleConcurrentCascades() throws Exception {
    // Create schema first
    session.getMetadata().getSchema().createVertexClass("Project");

    // Create multiple test entities
    session.begin();
    // Create projects using SQL execute
    var result1 = session.execute("CREATE VERTEX Project SET name = 'Project1'");
    var project1Rid = result1.next().getIdentity();
    result1.close(); // Close the result set

    var result2 = session.execute("CREATE VERTEX Project SET name = 'Project2'");
    var project2Rid = result2.next().getIdentity();
    result2.close(); // Close the result set

    session.commit();

    assertNotNull("Project1 should be created", project1Rid);
    assertNotNull("Project2 should be created", project2Rid);

    // Schedule multiple concurrent cascades
    var future1 = backgroundManager.scheduleCascadeDelete(
        project1Rid,
        CascadeDeletePolicy.CASCADE_EAGER
    );

    var future2 = backgroundManager.scheduleCascadeDelete(
        project2Rid,
        CascadeDeletePolicy.CASCADE_EAGER
    );

    // Wait for both to complete
    var cascadeResult1 = future1.get(10, java.util.concurrent.TimeUnit.SECONDS);
    var cascadeResult2 = future2.get(10, java.util.concurrent.TimeUnit.SECONDS);

    assertTrue("First cascade should succeed", cascadeResult1.success());
    assertTrue("Second cascade should succeed", cascadeResult2.success());

    // Verify stats
    var stats = backgroundManager.getStats();
    assertEquals("Should have submitted 2 tasks", 2, stats.totalTasksSubmitted());
  }

  @Test
  public void testBackgroundManagerStats() {
    var initialStats = backgroundManager.getStats();

    assertEquals("Initial task count should be 0", 0, initialStats.totalTasksSubmitted());
    assertTrue("Initial executor should be active", initialStats.executorActive());
    assertTrue("Initial session pool should be active", initialStats.sessionPoolActive());
    assertTrue("Should be fully operational", initialStats.isFullyOperational());

    // After shutdown
    backgroundManager.shutdown();
    var shutdownStats = backgroundManager.getStats();

    assertFalse("Executor should be inactive after shutdown", shutdownStats.executorActive());
    assertFalse("Should not be fully operational after shutdown",
        shutdownStats.isFullyOperational());
  }


}