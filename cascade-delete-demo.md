# YouTrackDB Cascade Delete Demo

## Starting the Console
```bash
# Run the console
docker run -it youtrackdb/youtrackdb-console

# Or build and run locally
mvn clean package -DskipTests
java -jar console-distribution/target/youtrackdb-console-0.5.0-SNAPSHOT.jar
```

## Setting up Test Data
```groovy
// In the console, create test schema
graph = YTDBDemoGraphFactory.createEmpty(ytdb)
g = graph.traversal()

// Create issue tracking schema
g.addV("Project").property("name", "My Project").property("id", "proj1").as("project")
 .addV("Issue").property("title", "Bug Fix").property("id", "issue1").as("issue1")  
 .addV("Issue").property("title", "Feature").property("id", "issue2").as("issue2")
 .addV("Comment").property("text", "First comment").property("id", "comment1").as("comment1")
 .addV("Comment").property("text", "Second comment").property("id", "comment2").as("comment2")
 .addE("contains").from("project").to("issue1")
 .addE("contains").from("project").to("issue2") 
 .addE("has_comment").from("issue1").to("comment1")
 .addE("has_comment").from("issue1").to("comment2")
 .iterate()

// Verify the data
g.V().hasLabel("Project").valueMap()
g.V().hasLabel("Issue").valueMap()  
g.V().hasLabel("Comment").valueMap()

// Test basic deletion (without cascade)
project = g.V().hasLabel("Project").has("id", "proj1").next()
project.remove() // This should only delete the project vertex

// Check what remains
g.V().hasLabel("Issue").count()    // Should still be 2
g.V().hasLabel("Comment").count()  // Should still be 2
```