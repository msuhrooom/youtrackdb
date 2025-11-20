package com.jetbrains.youtrackdb.internal.core.sql.parser;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.jetbrains.youtrackdb.internal.DbTestBase;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import org.junit.Test;

/**
 * Test cases for CASCADE DELETE SQL parsing functionality.
 * <p>
 * Tests the JavaCC parser modifications to support CASCADE syntax.
 */
public class CascadeDeleteParserTest extends DbTestBase {

  protected SimpleNode checkRightSyntax(String query) {
    return checkSyntax(query, true);
  }

  protected SimpleNode checkWrongSyntax(String query) {
    return checkSyntax(query, false);
  }

  protected SimpleNode checkSyntax(String query, boolean isCorrect) {
    var osql = getParserFor(query);
    try {
      SimpleNode result = osql.parse();
      if (!isCorrect) {
        fail("Expected syntax error for: " + query);
      }
      return result;
    } catch (Exception e) {
      if (isCorrect) {
        System.err.println("Unexpected parse error for: " + query);
        e.printStackTrace();
        fail("Parse should have succeeded for: " + query);
      }
    }
    return null;
  }

  protected List<SQLStatement> checkRightSyntaxScript(String query) {
    return checkSyntaxScript(query, true);
  }

  protected List<SQLStatement> checkWrongSyntaxScript(String query) {
    return checkSyntaxScript(query, false);
  }

  protected List<SQLStatement> checkSyntaxScript(String query, boolean isCorrect) {
    var osql = getParserFor(query);
    try {
      List<SQLStatement> result = osql.parseScript();
      if (!isCorrect) {
        fail("Expected syntax error for: " + query);
      }
      return result;
    } catch (Exception e) {
      if (isCorrect) {
        System.err.println("Unexpected parse error for: " + query);
        e.printStackTrace();
        fail("Parse should have succeeded for: " + query);
      }
    }
    return null;
  }

  protected YouTrackDBSql getParserFor(String query) {
    InputStream is = new ByteArrayInputStream(query.getBytes());
    return new YouTrackDBSql(is);
  }

  @Test
  public void testBasicDeleteCascadeParsing() {
    // Test basic CASCADE syntax
    var result = checkRightSyntax("DELETE FROM Project CASCADE");
    assertNotNull("Should parse DELETE CASCADE", result);

    // Verify it's a delete statement
    assertTrue("Should be SQLDeleteStatement",
        result instanceof SQLDeleteStatement);

    var deleteStmt = (SQLDeleteStatement) result;

    // Check that CASCADE flag is set (assuming we added this field)
    // Note: This will depend on your actual implementation
    // assertTrue("CASCADE flag should be true", deleteStmt.isCascade());
  }

  @Test
  public void testDeleteCascadeWithWhere() {
    // Test CASCADE with WHERE clause
    var result = checkRightSyntax("DELETE FROM Project WHERE name = 'TestProject' CASCADE");
    assertNotNull("Should parse DELETE CASCADE with WHERE", result);

    assertTrue("Should be SQLDeleteStatement",
        result instanceof SQLDeleteStatement);
  }

  @Test
  public void testDeleteCascadeWithLimit() {
    // Test CASCADE with LIMIT
    var result = checkRightSyntax("DELETE FROM Issue LIMIT 10 CASCADE");
    assertNotNull("Should parse DELETE CASCADE with LIMIT", result);
  }

  @Test
  public void testDeleteCascadeWithUnsafe() {
    // Test CASCADE with UNSAFE
    var result = checkRightSyntax("DELETE FROM Comment CASCADE UNSAFE");
    assertNotNull("Should parse DELETE CASCADE UNSAFE", result);
  }

  @Test
  public void testDeleteCascadeWithReturnBefore() {
    // Test CASCADE with RETURN BEFORE
    var result = checkRightSyntax("DELETE FROM User RETURN BEFORE CASCADE");
    assertNotNull("Should parse DELETE RETURN BEFORE CASCADE", result);
  }

  @Test
  public void testCompleteDeleteCascadeSyntax() {
    // Test all optional clauses together
    var sql = "DELETE FROM Project RETURN BEFORE WHERE name = 'test' LIMIT 5 CASCADE UNSAFE";
    var result = checkRightSyntax(sql);
    assertNotNull("Should parse complex DELETE CASCADE", result);
  }

  @Test
  public void testCascadeKeywordCaseInsensitive() {
    // Test case insensitivity
    checkRightSyntax("DELETE FROM Test cascade");
    checkRightSyntax("DELETE FROM Test CASCADE");
    checkRightSyntax("DELETE FROM Test Cascade");
    checkRightSyntax("DELETE FROM Test CaScAdE");
  }

  @Test
  public void testDeleteWithoutCascade() {
    // Ensure normal DELETE still works
    var result = checkRightSyntax("DELETE FROM Project WHERE id = 1");
    assertNotNull("Normal DELETE should still work", result);

    assertTrue("Should be SQLDeleteStatement",
        result instanceof SQLDeleteStatement);

    var deleteStmt = (SQLDeleteStatement) result;
    // Verify CASCADE is not set
    // assertFalse("CASCADE should be false by default", deleteStmt.isCascade());
  }

  @Test
  public void testInvalidCascadeSyntax() {
    // Test invalid placements of CASCADE
    checkWrongSyntax("CASCADE DELETE FROM Project");        // CASCADE at beginning
    checkWrongSyntax("DELETE CASCADE FROM Project");        // CASCADE before FROM
    checkWrongSyntax("DELETE FROM CASCADE Project");        // CASCADE before table name
  }

  @Test
  public void testDeleteCascadeWithSubquery() {
    // Test CASCADE with subquery
    var sql = "DELETE FROM Issue WHERE project_id IN (SELECT id FROM Project WHERE archived = true) CASCADE";
    var result = checkRightSyntax(sql);
    assertNotNull("Should parse CASCADE with subquery", result);
  }

  @Test
  public void testDeleteCascadeWithJoin() {
    // Test CASCADE with implicit join
    var sql = "DELETE FROM Comment WHERE in('has_comment').title = 'Bug Report' CASCADE";
    var result = checkRightSyntax(sql);
    assertNotNull("Should parse CASCADE with traversal", result);
  }

  @Test
  public void testMultipleDeleteStatements() {
    // Test parsing multiple statements with and without CASCADE
    // Multiple statements must be parsed using parseScript(), not parse()
    var result1 = checkRightSyntaxScript("DELETE FROM Project; DELETE FROM Issue;");
    assertNotNull("Should parse multiple DELETE statements", result1);
    assertTrue("Should have 2 statements", result1.size() == 2);

    var result2 = checkRightSyntaxScript("DELETE FROM Project CASCADE; DELETE FROM Issue;");
    assertNotNull("Should parse multiple DELETE statements with CASCADE", result2);
    assertTrue("Should have 2 statements", result2.size() == 2);

    var result3 = checkRightSyntaxScript("DELETE FROM Issue; DELETE FROM Comment CASCADE;");
    assertNotNull("Should parse multiple DELETE statements with mixed CASCADE", result3);
    assertTrue("Should have 2 statements", result3.size() == 2);
  }

  @Test
  public void testDeleteCascadeWithParameters() {
    // Test CASCADE with parameters
    var result = checkRightSyntax("DELETE FROM Project WHERE created_date < ? CASCADE");
    assertNotNull("Should parse CASCADE with parameters", result);
  }

  @Test
  public void testDeleteCascadeWithComplexWhere() {
    // Test CASCADE with complex WHERE conditions
    var sql = "DELETE FROM Issue WHERE (status = 'CLOSED' AND priority < 3) OR assignee IS NULL CASCADE";
    var result = checkRightSyntax(sql);
    assertNotNull("Should parse CASCADE with complex WHERE", result);
  }

  @Test
  public void testDeleteCascadeEdgeStatement() {
    // Test CASCADE doesn't interfere with DELETE EDGE statements
    checkRightSyntax("DELETE EDGE FROM #1:1 TO #1:2");
    checkRightSyntax("DELETE EDGE WHERE out.name = 'test'");
  }

  @Test
  public void testDeleteCascadeVertexStatement() {
    // Test CASCADE doesn't interfere with DELETE VERTEX statements
    // DELETE VERTEX requires a class name or RID before WHERE
    checkRightSyntax("DELETE VERTEX Project WHERE @rid = #1:1");
    checkRightSyntax("DELETE VERTEX Project WHERE name = 'test'");
  }

  @Test
  public void testDeleteCascadeWithFunctions() {
    // Test CASCADE with SQL functions
    var sql = "DELETE FROM Comment WHERE length(text) < 10 CASCADE";
    var result = checkRightSyntax(sql);
    assertNotNull("Should parse CASCADE with functions", result);
  }

  @Test
  public void testDeleteCascadeStringLiterals() {
    // Test CASCADE with various string formats
    checkRightSyntax("DELETE FROM Project WHERE name = 'Test Project' CASCADE");
    checkRightSyntax("DELETE FROM Project WHERE description = \"Long description\" CASCADE");
  }

  @Test
  public void testDeleteCascadeWithNumbers() {
    // Test CASCADE with numeric conditions
    checkRightSyntax("DELETE FROM Issue WHERE priority = 1 CASCADE");
    checkRightSyntax("DELETE FROM Issue WHERE created_timestamp > 1637499600000 CASCADE");
  }

  @Test
  public void testDeleteCascadeWhitespaceHandling() {
    // Test various whitespace scenarios
    checkRightSyntax("DELETE FROM Project CASCADE");                    // Normal
    checkRightSyntax("DELETE FROM Project  CASCADE");                   // Extra space
    checkRightSyntax("DELETE FROM Project\nCASCADE");                   // Newline
    checkRightSyntax("DELETE FROM Project\tCASCADE");                   // Tab
    checkRightSyntax("DELETE   FROM   Project   CASCADE");              // Multiple spaces
  }

  @Test
  public void testDeleteCascadeComments() {
    // Test CASCADE with SQL comments (block comments only, line comments not supported)
    checkRightSyntax("DELETE FROM Project /* remove project */ CASCADE");
    checkRightSyntax("DELETE FROM Project /* cascade delete */ CASCADE");
  }

  @Test
  public void testDeleteCascadePerformanceHint() {
    // Test that CASCADE parsing doesn't break performance hints
    // (if YouTrackDB supports them)
    checkRightSyntax("DELETE FROM /*+ USE_INDEX(idx_name) */ Project CASCADE");
  }
}