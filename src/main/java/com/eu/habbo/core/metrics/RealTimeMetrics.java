package com.eu.habbo.core.metrics;

import com.eu.habbo.util.collections.OptimizedConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Real-time metrics collection and reporting system for Arcturus Morningstar Reworked
 * Provides high-performance metrics with minimal overhead
 */
public class RealTimeMetrics {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(RealTimeMetrics.class);
    
    // Singleton instance
    private static final RealTimeMetrics INSTANCE = new RealTimeMetrics();
    
    // Metrics storage
    private final OptimizedConcurrentMap<String, MetricValue> metrics;
    private final OptimizedConcurrentMap<String, TimerMetric> timers;
    private final ScheduledExecutorService reportingScheduler;
    
    // Global counters using LongAdder for high concurrency
    private final LongAdder totalPacketsProcessed = new LongAdder();
    private final LongAdder totalDatabaseQueries = new LongAdder();
    private final LongAdder totalRoomOperations = new LongAdder();
    private final LongAdder totalUserActions = new LongAdder();
    
    // System metrics
    private final AtomicLong peakMemoryUsage = new AtomicLong(0);
    private final AtomicLong peakThreadCount = new AtomicLong(0);
    
    private volatile boolean enabled = true;
    private volatile long lastReportTime = System.currentTimeMillis();
    
    private RealTimeMetrics() {
        this.metrics = new OptimizedConcurrentMap<>("GlobalMetrics", 256);
        this.timers = new OptimizedConcurrentMap<>("TimerMetrics", 128);
        this.reportingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Metrics-Reporter");
            t.setDaemon(true);
            return t;
        });
        
        // Start periodic reporting
        startPeriodicReporting();
    }
    
    public static RealTimeMetrics getInstance() {
        return INSTANCE;
    }
    
    /**
     * Increment a counter metric
     */
    public void incrementCounter(String name) {
        incrementCounter(name, 1);
    }
    
    /**
     * Increment a counter by specific amount
     */
    public void incrementCounter(String name, long amount) {
        if (!enabled) return;
        
        metrics.computeIfAbsent(name, k -> new MetricValue(MetricType.COUNTER))
               .addValue(amount);
    }
    
    /**
     * Set a gauge value
     */
    public void setGauge(String name, long value) {
        if (!enabled) return;
        
        metrics.computeIfAbsent(name, k -> new MetricValue(MetricType.GAUGE))
               .setValue(value);
    }
    
    /**
     * Record a histogram value
     */
    public void recordHistogram(String name, long value) {
        if (!enabled) return;
        
        metrics.computeIfAbsent(name, k -> new MetricValue(MetricType.HISTOGRAM))
               .addValue(value);
    }
    
    /**
     * Start timing an operation
     */
    public TimerContext startTimer(String name) {
        if (!enabled) return TimerContext.NOOP;
        
        TimerMetric timer = timers.computeIfAbsent(name, k -> new TimerMetric(k));
        return new TimerContext(timer);
    }
    
    /**
     * Record execution time
     */
    public void recordTime(String name, long durationNanos) {
        if (!enabled) return;
        
        timers.computeIfAbsent(name, k -> new TimerMetric(k))
              .recordTime(durationNanos);
    }
    
    // Global metric shortcuts
    public void incrementPackets() { totalPacketsProcessed.increment(); }
    public void incrementDatabaseQueries() { totalDatabaseQueries.increment(); }
    public void incrementRoomOperations() { totalRoomOperations.increment(); }
    public void incrementUserActions() { totalUserActions.increment(); }
    
    /**
     * Update system metrics
     */
    public void updateSystemMetrics() {
        if (!enabled) return;
        
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long threadCount = Thread.activeCount();
        
        // Track peak values
        peakMemoryUsage.updateAndGet(current -> Math.max(current, usedMemory));
        peakThreadCount.updateAndGet(current -> Math.max(current, threadCount));
        
        // Set current values
        setGauge("system.memory.used", usedMemory);
        setGauge("system.memory.free", runtime.freeMemory());
        setGauge("system.memory.total", runtime.totalMemory());
        setGauge("system.memory.max", runtime.maxMemory());
        setGauge("system.threads.active", threadCount);
    }
    
    /**
     * Get metric value
     */
    public long getMetricValue(String name) {
        MetricValue metric = metrics.get(name);
        return metric != null ? metric.getValue() : 0L;
    }
    
    /**
     * Get timer statistics
     */
    public TimerStats getTimerStats(String name) {
        TimerMetric timer = timers.get(name);
        return timer != null ? timer.getStats() : null;
    }
    
    /**
     * Start periodic reporting
     */
    private void startPeriodicReporting() {
        // Report metrics every 5 minutes
        reportingScheduler.scheduleAtFixedRate(() -> {
            try {
                generateReport();
            } catch (Exception e) {
                LOGGER.error("Error generating metrics report", e);
            }
        }, 5, 5, TimeUnit.MINUTES);
        
        // Update system metrics every 30 seconds
        reportingScheduler.scheduleAtFixedRate(() -> {
            try {
                updateSystemMetrics();
            } catch (Exception e) {
                LOGGER.error("Error updating system metrics", e);
            }
        }, 30, 30, TimeUnit.SECONDS);
    }
    
    /**
     * Generate comprehensive metrics report
     */
    public void generateReport() {
        if (!enabled) return;
        
        StringBuilder report = new StringBuilder();
        report.append("\n=== Real-Time Metrics Report ===\n");
        
        // Global counters
        report.append(String.format("Global Counters:\n"));
        report.append(String.format("  Packets Processed: %,d\n", totalPacketsProcessed.sum()));
        report.append(String.format("  Database Queries: %,d\n", totalDatabaseQueries.sum()));
        report.append(String.format("  Room Operations: %,d\n", totalRoomOperations.sum()));
        report.append(String.format("  User Actions: %,d\n", totalUserActions.sum()));
        
        // System metrics
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        report.append(String.format("System Metrics:\n"));
        report.append(String.format("  Memory: %,d MB / %,d MB (%.1f%%)\n", 
            usedMemory / 1024 / 1024, 
            runtime.maxMemory() / 1024 / 1024,
            (double) usedMemory / runtime.maxMemory() * 100));
        report.append(String.format("  Peak Memory: %,d MB\n", peakMemoryUsage.get() / 1024 / 1024));
        report.append(String.format("  Active Threads: %d (Peak: %d)\n", 
            Thread.activeCount(), peakThreadCount.get()));
        
        // Top metrics by value
        report.append("Top Metrics:\n");
        metrics.entrySet().stream()
               .sorted((a, b) -> Long.compare(b.getValue().getValue(), a.getValue().getValue()))
               .limit(10)
               .forEach(entry -> report.append(String.format("  %s: %,d\n", 
                   entry.getKey(), entry.getValue().getValue())));
        
        // Top timers by average duration
        report.append("Slowest Operations:\n");
        timers.entrySet().stream()
              .filter(entry -> entry.getValue().getCount() > 0)
              .sorted((a, b) -> Double.compare(b.getValue().getAverageMs(), a.getValue().getAverageMs()))
              .limit(10)
              .forEach(entry -> {
                  TimerStats stats = entry.getValue().getStats();
                  report.append(String.format("  %s: %.2fms avg (count: %,d)\n", 
                      entry.getKey(), stats.averageMs, stats.count));
              });
        
        report.append("================================\n");
        
        LOGGER.info(report.toString());
        lastReportTime = System.currentTimeMillis();
    }
    
    /**
     * Reset all metrics
     */
    public void reset() {
        metrics.clear();
        timers.clear();
        totalPacketsProcessed.reset();
        totalDatabaseQueries.reset();
        totalRoomOperations.reset();
        totalUserActions.reset();
        peakMemoryUsage.set(0);
        peakThreadCount.set(0);
        LOGGER.info("All metrics have been reset");
    }
    
    /**
     * Enable/disable metrics collection
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        LOGGER.info("Metrics collection {}", enabled ? "enabled" : "disabled");
    }
    
    /**
     * Check if metrics are enabled
     */
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Shutdown metrics system
     */
    public void shutdown() {
        reportingScheduler.shutdown();
        try {
            if (!reportingScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                reportingScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            reportingScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        LOGGER.info("Real-time metrics system shut down");
    }
}