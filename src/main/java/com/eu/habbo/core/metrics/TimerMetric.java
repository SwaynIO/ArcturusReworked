package com.eu.habbo.core.metrics;

import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance timer metric for measuring operation durations
 */
public class TimerMetric {
    
    private final String name;
    private final LongAdder totalTimeNanos = new LongAdder();
    private final LongAdder count = new LongAdder();
    private final AtomicLong minTimeNanos = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxTimeNanos = new AtomicLong(0);
    
    // Moving average with exponential decay
    private volatile double exponentialAvgNanos = 0.0;
    private static final double ALPHA = 0.1; // Decay factor
    
    public TimerMetric(String name) {
        this.name = name;
    }
    
    /**
     * Record a timing measurement in nanoseconds
     */
    public void recordTime(long durationNanos) {
        totalTimeNanos.add(durationNanos);
        count.increment();
        
        // Update min/max
        minTimeNanos.updateAndGet(current -> Math.min(current, durationNanos));
        maxTimeNanos.updateAndGet(current -> Math.max(current, durationNanos));
        
        // Update exponential moving average
        updateExponentialAverage(durationNanos);
    }
    
    private void updateExponentialAverage(long durationNanos) {
        double current = exponentialAvgNanos;
        if (current == 0.0) {
            // First measurement
            exponentialAvgNanos = durationNanos;
        } else {
            // Exponential moving average: new_avg = α * new_value + (1-α) * old_avg
            exponentialAvgNanos = ALPHA * durationNanos + (1 - ALPHA) * current;
        }
    }
    
    /**
     * Get timer statistics
     */
    public TimerStats getStats() {
        long totalCount = count.sum();
        if (totalCount == 0) {
            return new TimerStats(name, 0, 0, 0, 0, 0, 0);
        }
        
        long totalTime = totalTimeNanos.sum();
        long min = minTimeNanos.get();
        long max = maxTimeNanos.get();
        
        // Convert to milliseconds for readability
        double avgMs = (totalTime / (double) totalCount) / 1_000_000.0;
        double minMs = min == Long.MAX_VALUE ? 0 : min / 1_000_000.0;
        double maxMs = max / 1_000_000.0;
        double expAvgMs = exponentialAvgNanos / 1_000_000.0;
        
        return new TimerStats(name, totalCount, avgMs, minMs, maxMs, expAvgMs, totalTime);
    }
    
    /**
     * Get average duration in milliseconds
     */
    public double getAverageMs() {
        long totalCount = count.sum();
        if (totalCount == 0) return 0.0;
        
        return (totalTimeNanos.sum() / (double) totalCount) / 1_000_000.0;
    }
    
    /**
     * Get total count of measurements
     */
    public long getCount() {
        return count.sum();
    }
    
    /**
     * Get timer name
     */
    public String getName() {
        return name;
    }
    
    /**
     * Reset timer
     */
    public void reset() {
        totalTimeNanos.reset();
        count.reset();
        minTimeNanos.set(Long.MAX_VALUE);
        maxTimeNanos.set(0);
        exponentialAvgNanos = 0.0;
    }
    
    @Override
    public String toString() {
        TimerStats stats = getStats();
        return String.format("TimerMetric{name='%s', count=%d, avgMs=%.2f, minMs=%.2f, maxMs=%.2f}", 
                           name, stats.count, stats.averageMs, stats.minMs, stats.maxMs);
    }
}

/**
 * Timer statistics data class
 */
class TimerStats {
    public final String name;
    public final long count;
    public final double averageMs;
    public final double minMs;
    public final double maxMs;
    public final double exponentialAvgMs;
    public final long totalTimeNanos;
    
    public TimerStats(String name, long count, double averageMs, double minMs, 
                     double maxMs, double exponentialAvgMs, long totalTimeNanos) {
        this.name = name;
        this.count = count;
        this.averageMs = averageMs;
        this.minMs = minMs;
        this.maxMs = maxMs;
        this.exponentialAvgMs = exponentialAvgMs;
        this.totalTimeNanos = totalTimeNanos;
    }
    
    @Override
    public String toString() {
        return String.format("TimerStats{name='%s', count=%d, avg=%.2fms, min=%.2fms, max=%.2fms, expAvg=%.2fms}",
                           name, count, averageMs, minMs, maxMs, exponentialAvgMs);
    }
}