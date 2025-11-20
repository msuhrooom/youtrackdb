package com.jetbrains.youtrackdb.internal.core.sql.executor;

/**
 * Policy configuration for cascade deletion behavior.
 * 
 * Follows YouTrackDB's enum pattern for configuration options.
 * Defines both the cascade strategy and execution mode.
 */
public enum CascadeDeletePolicy {
    
    /**
     * No cascading - only delete the target entity.
     * Relationships are removed but connected entities remain.
     */
    NONE(false),
    
    /**
     * Eager cascade deletion within the same transaction.
     * WARNING: Can cause long-running transactions for large graphs.
     * Use with caution in YouTrackDB's issue tracking scenarios.
     */
    CASCADE_EAGER(false),
    
    /**
     * Lazy cascade deletion via background processing.
     * RECOMMENDED: Schedules cascade deletion for background jobs.
     * Keeps user-facing operations fast and handles large graphs gracefully.
     */
    CASCADE_LAZY(true),
    
    /**
     * Hybrid approach: eager for small cascades, lazy for large ones.
     * Attempts eager deletion first, falls back to lazy if cascade is too large.
     */
    CASCADE_HYBRID(true),
    
    /**
     * Prevent deletion if dependent entities exist.
     * Throws exception rather than cascading.
     */
    RESTRICT(false),
    
    /**
     * Set foreign key references to null instead of deleting.
     * Useful for preserving data while removing relationships.
     */
    SET_NULL(false),
    
    /**
     * Set foreign key references to a default value.
     */
    SET_DEFAULT(false);
    
    private final boolean lazy;
    
    CascadeDeletePolicy(boolean lazy) {
        this.lazy = lazy;
    }
    
    /**
     * Returns true if this policy uses lazy/background processing.
     */
    public boolean isLazy() {
        return lazy;
    }
    
    /**
     * Returns true if this policy performs actual cascading deletion.
     */
    public boolean isCascading() {
        return this == CASCADE_EAGER || this == CASCADE_LAZY || this == CASCADE_HYBRID;
    }
    
    /**
     * Returns the recommended policy for YouTrackDB issue tracking scenarios.
     * Lazy cascade is preferred to handle large connected graphs without
     * blocking user operations.
     */
    public static CascadeDeletePolicy getRecommendedForIssueTracking() {
        return CASCADE_LAZY;
    }
}