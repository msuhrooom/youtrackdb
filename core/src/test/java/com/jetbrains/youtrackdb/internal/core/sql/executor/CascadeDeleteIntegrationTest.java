package com.jetbrains.youtrackdb.internal.core.sql.executor;

import static org.junit.Assert.*;

import com.jetbrains.youtrackdb.api.schema.PropertyType;
import com.jetbrains.youtrackdb.internal.DbTestBase;
import com.jetbrains.youtrackdb.api.record.Vertex;
import com.jetbrains.youtrackdb.api.record.RID;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.ArrayList;

/**
 * Integration tests for cascade deletion using SQL commands.
 * 
 * Tests the complete flow from SQL parsing through execution.
 */
public class CascadeDeleteIntegrationTest extends DbTestBase {
    
    @Before 
    public void setupIssueTrackingSchema() {
        // Create schema that mimics real issue tracking
        session.begin();
        
        // Create vertex classes
        var schema = session.getMetadata().getSchema();
        schema.createVertexClass("Project");
        schema.createVertexClass("Issue");  
        schema.createVertexClass("Comment");
        schema.createVertexClass("User");
        
        // Create some properties
        var projectClass = schema.getClass("Project");
        projectClass.createProperty("name", PropertyType.valueOf("STRING"));
        projectClass.createProperty("description", PropertyType.valueOf("STRING"));
        
        var issueClass = schema.getClass("Issue");
        issueClass.createProperty("title", PropertyType.valueOf("STRING"));
        issueClass.createProperty("status", PropertyType.valueOf("STRING"));
        issueClass.createProperty("priority", PropertyType.valueOf("INTEGER"));
        
        var commentClass = schema.getClass("Comment");
        commentClass.createProperty("text", PropertyType.valueOf("STRING"));
        commentClass.createProperty("timestamp", PropertyType.valueOf("DATETIME"));
        
        var userClass = schema.getClass("User");
        userClass.createProperty("username", PropertyType.valueOf("STRING"));
        userClass.createProperty("email", PropertyType.valueOf("STRING"));
        
        session.commit();
    }
    
    @Test
    public void testCreateIssueTrackingData() {
        // Create realistic issue tracking data
        session.begin();
        
        // Create users
        var admin = session.newVertex("User");
        admin.setProperty("username", "admin");
        admin.setProperty("email", "admin@company.com");
        
        var developer = session.newVertex("User");
        developer.setProperty("username", "john.doe");
        developer.setProperty("email", "john.doe@company.com");
        
        // Create project
        var project = session.newVertex("Project");
        project.setProperty("name", "YouTrackDB v2.0");
        project.setProperty("description", "Next generation graph database");
        
        // Create issues
        var bug = session.newVertex("Issue");
        bug.setProperty("title", "Memory leak in traversal");
        bug.setProperty("status", "OPEN");
        bug.setProperty("priority", 1);
        
        var feature = session.newVertex("Issue");
        feature.setProperty("title", "Add cascade delete");
        feature.setProperty("status", "IN_PROGRESS");
        feature.setProperty("priority", 2);
        
        // Create comments
        var comment1 = session.newVertex("Comment");
        comment1.setProperty("text", "This affects production performance");
        
        var comment2 = session.newVertex("Comment");
        comment2.setProperty("text", "Working on a fix");
        
        var comment3 = session.newVertex("Comment");
        comment3.setProperty("text", "Initial implementation completed");
        
        // Create relationships
        project.addEdge(bug, "contains");
        project.addEdge(feature, "contains");
        
        bug.addEdge(developer, "assigned_to");
        feature.addEdge(developer, "assigned_to");
        
        bug.addEdge(admin, "created_by");
        feature.addEdge(admin, "created_by");
        
        bug.addEdge(comment1, "has_comment");
        bug.addEdge(comment2, "has_comment");
        feature.addEdge(comment3, "has_comment");
        
        comment1.addEdge(admin, "authored_by");
        comment2.addEdge(developer, "authored_by");
        comment3.addEdge(developer, "authored_by");
        
        session.commit();
        
        // Verify data was created correctly
        assertEquals("Should have 1 project", 1, session.countClass("Project"));
        assertEquals("Should have 2 issues", 2, session.countClass("Issue"));
        assertEquals("Should have 3 comments", 3, session.countClass("Comment"));
        assertEquals("Should have 2 users", 2, session.countClass("User"));
        
        // Test queries work
        session.begin();
        
        // Find project issues
        var projectIssues = session.query(
            "SELECT expand(out('contains')) FROM Project WHERE name = 'YouTrackDB v2.0'"
        );
        var issueCount = 0;
        while (projectIssues.hasNext()) {
            var issue = projectIssues.next();
            assertNotNull("Issue should exist", issue.getProperty("title"));
            issueCount++;
        }
        projectIssues.close();
        assertEquals("Project should have 2 issues", 2, issueCount);
        
        // Find issue comments  
        var issueComments = session.query(
            "SELECT expand(out('has_comment')) FROM Issue WHERE title = 'Memory leak in traversal'"
        );
        var commentCount = 0;
        while (issueComments.hasNext()) {
            var comment = issueComments.next();
            assertNotNull("Comment should exist", comment.getProperty("text"));
            commentCount++;
        }
        issueComments.close();
        assertEquals("Bug should have 2 comments", 2, commentCount);
        
        session.commit();
    }
    
    @Test
    public void testBasicSQLDelete() {
        // First create some test data
        testCreateIssueTrackingData();
        
        // Test basic SQL DELETE (no cascade)
        session.begin();
        
        // Delete one comment
        var result = session.execute("DELETE FROM Comment WHERE text = 'This affects production performance'");
        var deleteCount = result.next().<Long>getProperty("count");
        result.close();
        
        assertEquals("Should delete 1 comment", 1L, deleteCount.longValue());
        assertEquals("Should have 2 comments remaining", 2, session.countClass("Comment"));
        
        session.commit();
    }
    
    @Test
    public void testSQLDeleteWithWhere() {
        // Create test data
        testCreateIssueTrackingData();
        
        session.begin();
        
        // Delete issues with specific priority
        var result = session.execute("DELETE FROM Issue WHERE priority = 1");
        var deleteCount = result.next().<Long>getProperty("count");
        result.close();
        
        assertEquals("Should delete 1 issue", 1L, deleteCount.longValue());
        assertEquals("Should have 1 issue remaining", 1, session.countClass("Issue"));
        
        // Verify the right issue was deleted
        var remainingIssues = session.query("SELECT FROM Issue");
        assertTrue("Should have remaining issue", remainingIssues.hasNext());
        var issue = remainingIssues.next();
        assertEquals("Should be the feature issue", "Add cascade delete", issue.getProperty("title"));
        remainingIssues.close();
        
        session.commit();
    }
    
    @Test  
    public void testComplexQuery() {
        // Create test data
        testCreateIssueTrackingData();
        
        session.begin();
        
        // Find all comments by a specific user
        var commentsQuery = session.query(
            "SELECT FROM Comment WHERE in('authored_by').username = 'john.doe'"
        );
        
        var userComments = new ArrayList<String>();
        while (commentsQuery.hasNext()) {
            var comment = commentsQuery.next();
            userComments.add((String) comment.getProperty("text"));
        }
        commentsQuery.close();
        
        assertEquals("John should have 2 comments", 2, userComments.size());
        assertTrue("Should include work comment", 
                  userComments.contains("Working on a fix"));
        assertTrue("Should include implementation comment",
                  userComments.contains("Initial implementation completed"));
        
        session.commit();
    }
    
    @Test
    public void testFutureCascadeDeleteSQL() {
        // This test shows what cascade delete SQL might look like
        testCreateIssueTrackingData();
        
        session.begin();
        
        // TODO: This is what we want to support in the future:
        // session.execute("DELETE FROM Project WHERE name = 'YouTrackDB v2.0' CASCADE");
        
        // For now, test manual cascade deletion logic
        var projectQuery = session.query("SELECT FROM Project WHERE name = 'YouTrackDB v2.0'");
        assertTrue("Project should exist", projectQuery.hasNext());
        var project = projectQuery.next();
        var projectId = project.getIdentity();
        projectQuery.close();
        
        // Manually find and delete dependent entities (simulating cascade)
        // 1. Find and delete comments
        var commentIds = new ArrayList<RID>();
        var commentsQuery = session.query(
            "SELECT FROM Comment WHERE in('has_comment').in('contains').@rid = " + projectId
        );
        while (commentsQuery.hasNext()) {
            commentIds.add(commentsQuery.next().getIdentity());
        }
        commentsQuery.close();
        
        for (var commentId : commentIds) {
            session.execute("DELETE FROM Comment WHERE @rid = " + commentId).close();
        }
        
        // 2. Find and delete issues
        var issueIds = new ArrayList<RID>();
        var issuesQuery = session.query(
            "SELECT FROM Issue WHERE in('contains').@rid = " + projectId  
        );
        while (issuesQuery.hasNext()) {
            issueIds.add(issuesQuery.next().getIdentity());
        }
        issuesQuery.close();
        
        for (var issueId : issueIds) {
            session.execute("DELETE FROM Issue WHERE @rid = " + issueId).close();
        }
        
        // 3. Delete the project
        session.execute("DELETE FROM Project WHERE @rid = " + projectId).close();
        
        session.commit();
        
        // Verify everything was deleted
        assertEquals("All projects should be deleted", 0, session.countClass("Project"));
        assertEquals("All issues should be deleted", 0, session.countClass("Issue"));
        assertEquals("All comments should be deleted", 0, session.countClass("Comment"));
        assertEquals("Users should remain", 2, session.countClass("User")); // Users not cascaded
    }
}