package com.jetbrains.youtrackdb.internal.core.sql.executor;

/**
 * Defines the cascade behavior when deleting entities with relationships.
 * 
 * These policies control how deletion operations propagate through 
 * the graph based on relationship types and business rules.
 */
public enum CascadePolicy {
    
    /**
     * No cascading - only delete the target entity.
     * Relationships are removed but connected entities remain.
     */
    NONE,
    
    /**
     * Cascade deletion to dependent entities.
     * Example: Deleting a project cascades to delete all its issues.
     */
    CASCADE,
    
    /**
     * Prevent deletion if dependent entities exist.
     * Example: Cannot delete a user who has created issues.
     */
    RESTRICT,
    
    /**
     * Set foreign key references to null instead of deleting.
     * Example: Deleting a user sets issue.assignee to null.
     */
    SET_NULL,
    
    /**
     * Set foreign key references to a default value.
     * Example: Deleting a user assigns issues to default user.
     */
    SET_DEFAULT
}