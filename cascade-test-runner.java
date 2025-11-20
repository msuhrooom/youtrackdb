// Simple test runner to explore YouTrackDB
// Save as CascadeTestRunner.java and run with: javac -cp "youtrackdb-core-*.jar" CascadeTestRunner.java && java -cp ".:youtrackdb-core-*.jar" CascadeTestRunner

import com.jetbrains.youtrackdb.api.DatabaseType;
import com.jetbrains.youtrackdb.api.YourTracks;

public class CascadeTestRunner {
    public static void main(String[] args) throws Exception {
        System.out.println("=== YouTrackDB Cascade Delete Demo ===");
        
        // Create in-memory database
        try (var ytdb = YourTracks.instance("./target/test-data")) {
            ytdb.create("cascade-test", DatabaseType.MEMORY, "admin", "adminpwd", "admin");
            
            try (var session = ytdb.openDatabase("cascade-test", "admin", "adminpwd")) {
                
                // Test 1: Create schema
                System.out.println("1. Creating issue tracking schema...");
                session.begin();
                
                var schema = session.getMetadata().getSchema();
                var projectClass = schema.createVertexClass("Project");
                var issueClass = schema.createVertexClass("Issue");
                var commentClass = schema.createVertexClass("Comment");
                
                projectClass.createProperty("name", "STRING");
                issueClass.createProperty("title", "STRING");
                issueClass.createProperty("priority", "INTEGER");
                commentClass.createProperty("text", "STRING");
                
                session.commit();
                System.out.println("   ✓ Schema created");
                
                // Test 2: Insert test data
                System.out.println("2. Inserting test data...");
                session.begin();
                
                var project = session.newVertex("Project");
                project.setProperty("name", "Demo Project");
                
                var issue1 = session.newVertex("Issue");
                issue1.setProperty("title", "Bug #1");
                issue1.setProperty("priority", 1);
                
                var issue2 = session.newVertex("Issue");
                issue2.setProperty("title", "Feature #2");
                issue2.setProperty("priority", 2);
                
                var comment1 = session.newVertex("Comment");
                comment1.setProperty("text", "This is a critical bug");
                
                var comment2 = session.newVertex("Comment");
                comment2.setProperty("text", "Need more details");
                
                // Create relationships
                project.addEdge("contains", issue1);
                project.addEdge("contains", issue2);
                issue1.addEdge("has_comment", comment1);
                issue1.addEdge("has_comment", comment2);
                
                session.commit();
                System.out.println("   ✓ Created: 1 project, 2 issues, 2 comments");
                
                // Test 3: Query data
                System.out.println("3. Querying data...");
                session.begin();
                
                // Count entities
                System.out.println("   Projects: " + session.countClass("Project"));
                System.out.println("   Issues: " + session.countClass("Issue"));  
                System.out.println("   Comments: " + session.countClass("Comment"));
                
                // Query project issues
                var result = session.query("SELECT expand(out('contains')) FROM Project WHERE name = 'Demo Project'");
                System.out.println("   Project issues:");
                while (result.hasNext()) {
                    var issue = result.next();
                    System.out.println("     - " + issue.getProperty("title") + " (priority: " + issue.getProperty("priority") + ")");
                }
                result.close();
                
                // Query issue comments
                var commentResult = session.query("SELECT expand(out('has_comment')) FROM Issue WHERE title = 'Bug #1'");
                System.out.println("   Bug #1 comments:");
                while (commentResult.hasNext()) {
                    var comment = commentResult.next();
                    System.out.println("     - " + comment.getProperty("text"));
                }
                commentResult.close();
                
                session.commit();
                
                // Test 4: Basic deletion
                System.out.println("4. Testing basic deletion...");
                session.begin();
                
                // Delete one comment
                var deleteResult = session.execute("DELETE FROM Comment WHERE text = 'Need more details'");
                var deletedCount = deleteResult.next().<Long>getProperty("count");
                deleteResult.close();
                
                System.out.println("   Deleted " + deletedCount + " comment(s)");
                System.out.println("   Remaining comments: " + session.countClass("Comment"));
                
                session.commit();
                
                // Test 5: Complex deletion scenario
                System.out.println("5. Testing complex deletion scenario...");
                session.begin();
                
                // This simulates what cascade delete should do
                System.out.println("   Simulating cascade delete of project...");
                
                // Find project
                var projectQuery = session.query("SELECT FROM Project WHERE name = 'Demo Project'");
                if (projectQuery.hasNext()) {
                    var proj = projectQuery.next();
                    var projectId = proj.getIdentity();
                    
                    // Find all comments in project issues
                    var commentsQuery = session.query(
                        "SELECT FROM Comment WHERE in('has_comment').in('contains').@rid = " + projectId
                    );
                    var commentCount = 0;
                    while (commentsQuery.hasNext()) {
                        commentsQuery.next();
                        commentCount++;
                    }
                    commentsQuery.close();
                    
                    // Find all issues in project
                    var issuesQuery = session.query(
                        "SELECT FROM Issue WHERE in('contains').@rid = " + projectId
                    );
                    var issueCount = 0; 
                    while (issuesQuery.hasNext()) {
                        issuesQuery.next();
                        issueCount++;
                    }
                    issuesQuery.close();
                    
                    System.out.println("   Found " + issueCount + " issues and " + commentCount + " comments to cascade delete");
                    System.out.println("   In production, these would be deleted in background jobs");
                }
                projectQuery.close();
                
                session.commit();
                
                System.out.println("6. Final state:");
                System.out.println("   Projects: " + session.countClass("Project"));
                System.out.println("   Issues: " + session.countClass("Issue"));
                System.out.println("   Comments: " + session.countClass("Comment"));
                
                System.out.println("\n=== Demo completed successfully! ===");
                System.out.println("Next steps: Implement actual cascade delete feature");
            }
        }
    }
}