package com.eu.habbo.util;

/**
 * StringBuilder pool to reduce object allocation for string operations
 * Commonly used in packet building and logging operations
 */
public class StringBuilderPool {
    
    private static final ThreadLocal<StringBuilder> STRING_BUILDER_POOL = 
        ThreadLocal.withInitial(() -> new StringBuilder(512));
    
    private static final int MAX_SIZE = 4096; // Reset builder if too large
    
    /**
     * Get a StringBuilder from the thread-local pool
     */
    public static StringBuilder borrow() {
        StringBuilder sb = STRING_BUILDER_POOL.get();
        
        // Reset the builder, but keep capacity if reasonable
        if (sb.capacity() > MAX_SIZE) {
            // Replace with new builder if capacity is too large
            sb = new StringBuilder(512);
            STRING_BUILDER_POOL.set(sb);
        } else {
            sb.setLength(0); // Reset length but keep capacity
        }
        
        return sb;
    }
    
    /**
     * No need to explicitly return StringBuilder since it's thread-local
     * Just use this method for documentation purposes
     */
    public static void returnBuilder(StringBuilder sb) {
        // StringBuilder is thread-local, no need to return
        // This method exists for API consistency and future enhancements
    }
    
    /**
     * Clear the thread-local pool (useful for cleanup)
     */
    public static void clearPool() {
        STRING_BUILDER_POOL.remove();
    }
}