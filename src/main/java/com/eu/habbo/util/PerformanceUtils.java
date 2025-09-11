package com.eu.habbo.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Performance utilities and monitoring for Arcturus Morningstar Reworked
 * Provides tools for performance tracking and optimization
 */
public class PerformanceUtils {
    
    // Performance counters
    private static final AtomicLong PACKET_COUNT = new AtomicLong(0);
    private static final AtomicLong DATABASE_QUERY_COUNT = new AtomicLong(0);
    private static final AtomicLong ROOM_OPERATIONS_COUNT = new AtomicLong(0);
    
    // Performance timing
    private static final ConcurrentHashMap<String, Long> OPERATION_TIMES = new ConcurrentHashMap<>();
    
    /**
     * Start timing an operation
     */
    public static long startTiming() {
        return System.nanoTime();
    }
    
    /**
     * End timing and record the operation
     */
    public static void endTiming(String operation, long startTime) {
        long elapsed = System.nanoTime() - startTime;
        OPERATION_TIMES.put(operation, elapsed);
    }
    
    /**
     * Get average timing for an operation
     */
    public static long getAverageTiming(String operation) {
        return OPERATION_TIMES.getOrDefault(operation, 0L);
    }
    
    /**
     * Increment packet counter
     */
    public static void incrementPacketCount() {
        PACKET_COUNT.incrementAndGet();
    }
    
    /**
     * Increment database query counter
     */
    public static void incrementDatabaseQueryCount() {
        DATABASE_QUERY_COUNT.incrementAndGet();
    }
    
    /**
     * Increment room operations counter
     */
    public static void incrementRoomOperationsCount() {
        ROOM_OPERATIONS_COUNT.incrementAndGet();
    }
    
    /**
     * Get packet count
     */
    public static long getPacketCount() {
        return PACKET_COUNT.get();
    }
    
    /**
     * Get database query count
     */
    public static long getDatabaseQueryCount() {
        return DATABASE_QUERY_COUNT.get();
    }
    
    /**
     * Get room operations count
     */
    public static long getRoomOperationsCount() {
        return ROOM_OPERATIONS_COUNT.get();
    }
    
    /**
     * Reset all performance counters
     */
    public static void resetCounters() {
        PACKET_COUNT.set(0);
        DATABASE_QUERY_COUNT.set(0);
        ROOM_OPERATIONS_COUNT.set(0);
        OPERATION_TIMES.clear();
    }
    
    /**
     * Get performance summary
     */
    public static String getPerformanceSummary() {
        StringBuilder sb = StringBuilderPool.borrow();
        try {
            sb.append("=== Performance Summary ===\n");
            sb.append("Packets processed: ").append(PACKET_COUNT.get()).append('\n');
            sb.append("Database queries: ").append(DATABASE_QUERY_COUNT.get()).append('\n');
            sb.append("Room operations: ").append(ROOM_OPERATIONS_COUNT.get()).append('\n');
            sb.append("Tracked operations: ").append(OPERATION_TIMES.size()).append('\n');
            return sb.toString();
        } finally {
            StringBuilderPool.returnBuilder(sb);
        }
    }
}