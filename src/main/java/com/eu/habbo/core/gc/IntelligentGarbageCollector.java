package com.eu.habbo.core.gc;

import com.eu.habbo.core.metrics.RealTimeMetrics;
import com.eu.habbo.util.collections.OptimizedConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Intelligent garbage collection manager for Arcturus Morningstar Reworked
 * Provides smart GC tuning, monitoring, and optimization based on application patterns
 */
public class IntelligentGarbageCollector {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(IntelligentGarbageCollector.class);
    
    // GC monitoring components
    private final MemoryMXBean memoryMXBean;
    private final OptimizedConcurrentMap<String, GarbageCollectorMXBean> gcBeans;
    private final ScheduledExecutorService scheduler;
    private final RealTimeMetrics metrics;
    
    // GC statistics
    private final AtomicLong totalCollections = new AtomicLong(0);
    private final AtomicLong totalCollectionTime = new AtomicLong(0);
    private final AtomicLong forcedCollections = new AtomicLong(0);
    private final AtomicBoolean adaptiveMode = new AtomicBoolean(true);
    
    // Thresholds and configuration
    private volatile double memoryPressureThreshold = 0.85; // 85% memory usage
    private volatile double criticalMemoryThreshold = 0.95; // 95% memory usage
    private volatile long maxGcPauseMs = 200; // Maximum acceptable GC pause
    private volatile int gcFrequencyLimit = 10; // Max GC per minute
    
    // Performance tracking
    private final GCPerformanceTracker performanceTracker;
    private final MemoryPoolOptimizer memoryPoolOptimizer;
    private final GCTuningEngine tuningEngine;
    
    public IntelligentGarbageCollector() {
        this.memoryMXBean = ManagementFactory.getMemoryMXBean();
        this.gcBeans = new OptimizedConcurrentMap<>("GCBeans", 8);
        this.metrics = RealTimeMetrics.getInstance();
        this.performanceTracker = new GCPerformanceTracker();
        this.memoryPoolOptimizer = new MemoryPoolOptimizer();
        this.tuningEngine = new GCTuningEngine();
        
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "IntelligentGC-" + r.hashCode());
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY - 1); // High priority for GC monitoring
            return t;
        });
        
        initializeGCBeans();
        startMonitoring();
        
        LOGGER.info("Intelligent garbage collector initialized - adaptive mode: {}", adaptiveMode.get());
    }
    
    /**
     * Analyze current memory state and suggest GC if needed
     */
    public GCRecommendation analyzeAndRecommend() {
        MemoryUsage heapMemory = memoryMXBean.getHeapMemoryUsage();
        double memoryUtilization = (double) heapMemory.getUsed() / heapMemory.getMax();
        
        GCPerformanceStats stats = performanceTracker.getStats();
        GCRecommendation recommendation = new GCRecommendation();
        
        // Check memory pressure
        if (memoryUtilization > criticalMemoryThreshold) {
            recommendation.urgency = GCUrgency.CRITICAL;
            recommendation.action = GCAction.FULL_GC;
            recommendation.reason = String.format("Critical memory usage: %.1f%%", memoryUtilization * 100);
        } else if (memoryUtilization > memoryPressureThreshold) {
            recommendation.urgency = GCUrgency.HIGH;
            recommendation.action = GCAction.MINOR_GC;
            recommendation.reason = String.format("High memory pressure: %.1f%%", memoryUtilization * 100);
        } else if (stats.avgPauseTime > maxGcPauseMs) {
            recommendation.urgency = GCUrgency.MEDIUM;
            recommendation.action = GCAction.TUNE_PARAMETERS;
            recommendation.reason = String.format("High GC pause time: %.1fms", stats.avgPauseTime);
        } else {
            recommendation.urgency = GCUrgency.LOW;
            recommendation.action = GCAction.MONITOR;
            recommendation.reason = "Memory usage within normal range";
        }
        
        return recommendation;
    }
    
    /**
     * Execute intelligent garbage collection based on current conditions
     */
    public GCExecutionResult executeIntelligentGC() {
        GCRecommendation recommendation = analyzeAndRecommend();
        long startTime = System.currentTimeMillis();
        
        try {
            switch (recommendation.action) {
                case MINOR_GC:
                    return executeMinorGC(startTime);
                case FULL_GC:
                    return executeFullGC(startTime);
                case TUNE_PARAMETERS:
                    return executeTuning(startTime);
                default:
                    return new GCExecutionResult(false, 0, "No GC needed", recommendation.reason);
            }
        } catch (Exception e) {
            LOGGER.error("Error executing intelligent GC", e);
            return new GCExecutionResult(false, System.currentTimeMillis() - startTime, 
                                       "Error: " + e.getMessage(), recommendation.reason);
        }
    }
    
    /**
     * Force garbage collection with monitoring
     */
    public GCExecutionResult forceGarbageCollection(boolean fullGC) {
        long startTime = System.currentTimeMillis();
        MemoryUsage beforeHeap = memoryMXBean.getHeapMemoryUsage();
        
        try {
            if (fullGC) {
                System.gc(); // Request full GC
                forcedCollections.incrementAndGet();
            } else {
                // Try to trigger minor GC by allocating and releasing memory
                triggerMinorGC();
            }
            
            // Wait a bit for GC to complete
            Thread.sleep(100);
            
            MemoryUsage afterHeap = memoryMXBean.getHeapMemoryUsage();
            long executionTime = System.currentTimeMillis() - startTime;
            
            long memoryFreed = beforeHeap.getUsed() - afterHeap.getUsed();
            double effectiveness = memoryFreed > 0 ? (double) memoryFreed / beforeHeap.getUsed() : 0.0;
            
            String result = String.format("GC completed - freed %d MB (%.1f%% effective)",
                                        memoryFreed / 1024 / 1024, effectiveness * 100);
            
            metrics.recordTime("gc.forced.execution", executionTime * 1_000_000);
            metrics.recordHistogram("gc.memory.freed", memoryFreed);
            
            return new GCExecutionResult(true, executionTime, result, 
                                       fullGC ? "Full GC requested" : "Minor GC requested");
            
        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            return new GCExecutionResult(false, executionTime, "Error: " + e.getMessage(), 
                                       "Forced GC failed");
        }
    }
    
    /**
     * Get comprehensive GC statistics
     */
    public GCStatistics getGCStatistics() {
        MemoryUsage heapMemory = memoryMXBean.getHeapMemoryUsage();
        MemoryUsage nonHeapMemory = memoryMXBean.getNonHeapMemoryUsage();
        
        long totalCollections = 0;
        long totalCollectionTime = 0;
        
        for (GarbageCollectorMXBean gcBean : gcBeans.values()) {
            totalCollections += gcBean.getCollectionCount();
            totalCollectionTime += gcBean.getCollectionTime();
        }
        
        GCPerformanceStats performanceStats = performanceTracker.getStats();
        
        return new GCStatistics(
            heapMemory, nonHeapMemory,
            totalCollections, totalCollectionTime,
            forcedCollections.get(),
            performanceStats.avgPauseTime,
            performanceStats.maxPauseTime,
            performanceStats.gcFrequency,
            getMemoryUtilization(),
            adaptiveMode.get()
        );
    }
    
    /**
     * Configure GC parameters
     */
    public void configure(double memoryThreshold, double criticalThreshold, 
                         long maxPauseMs, boolean adaptiveEnabled) {
        this.memoryPressureThreshold = Math.max(0.5, Math.min(0.95, memoryThreshold));
        this.criticalMemoryThreshold = Math.max(memoryThreshold + 0.05, Math.min(0.99, criticalThreshold));
        this.maxGcPauseMs = Math.max(50, Math.min(5000, maxPauseMs));
        this.adaptiveMode.set(adaptiveEnabled);
        
        LOGGER.info("GC configured - memory threshold: {:.1f}%, critical: {:.1f}%, max pause: {}ms, adaptive: {}",
                   this.memoryPressureThreshold * 100, this.criticalMemoryThreshold * 100, 
                   this.maxGcPauseMs, adaptiveEnabled);
    }
    
    // Private helper methods
    
    private void initializeGCBeans() {
        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcBeans.put(gcBean.getName(), gcBean);
            LOGGER.debug("Registered GC bean: {}", gcBean.getName());
        }
    }
    
    private void startMonitoring() {
        // Monitor GC performance every 30 seconds
        scheduler.scheduleAtFixedRate(() -> {
            try {
                monitorGCPerformance();
            } catch (Exception e) {
                LOGGER.error("Error monitoring GC performance", e);
            }
        }, 30, 30, TimeUnit.SECONDS);
        
        // Adaptive tuning every 5 minutes
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (adaptiveMode.get()) {
                    performAdaptiveTuning();
                }
            } catch (Exception e) {
                LOGGER.error("Error in adaptive GC tuning", e);
            }
        }, 300, 300, TimeUnit.SECONDS);
        
        // Generate performance report every 15 minutes
        scheduler.scheduleAtFixedRate(() -> {
            try {
                generatePerformanceReport();
            } catch (Exception e) {
                LOGGER.error("Error generating GC performance report", e);
            }
        }, 900, 900, TimeUnit.SECONDS);
    }
    
    private void monitorGCPerformance() {
        for (GarbageCollectorMXBean gcBean : gcBeans.values()) {
            long collections = gcBean.getCollectionCount();
            long collectionTime = gcBean.getCollectionTime();
            
            performanceTracker.recordGCEvent(gcBean.getName(), collections, collectionTime);
        }
        
        // Update metrics
        MemoryUsage heapMemory = memoryMXBean.getHeapMemoryUsage();
        metrics.setGauge("gc.memory.heap.used", heapMemory.getUsed());
        metrics.setGauge("gc.memory.heap.max", heapMemory.getMax());
        metrics.setGauge("gc.memory.utilization", (long)(getMemoryUtilization() * 100));
    }
    
    private void performAdaptiveTuning() {
        GCPerformanceStats stats = performanceTracker.getStats();
        
        // Adjust thresholds based on performance
        if (stats.avgPauseTime > maxGcPauseMs) {
            // Reduce memory pressure threshold to trigger GC earlier
            memoryPressureThreshold = Math.max(0.6, memoryPressureThreshold - 0.05);
            LOGGER.info("Reduced memory threshold to {:.1f}% due to high GC pause times", 
                       memoryPressureThreshold * 100);
        } else if (stats.gcFrequency > gcFrequencyLimit) {
            // Increase threshold to reduce GC frequency
            memoryPressureThreshold = Math.min(0.9, memoryPressureThreshold + 0.05);
            LOGGER.info("Increased memory threshold to {:.1f}% due to high GC frequency", 
                       memoryPressureThreshold * 100);
        }
        
        // Apply memory pool optimizations
        memoryPoolOptimizer.optimize(stats);
        
        // Apply tuning engine recommendations
        tuningEngine.applyOptimizations(stats);
    }
    
    private GCExecutionResult executeMinorGC(long startTime) {
        try {
            triggerMinorGC();
            Thread.sleep(50); // Wait for GC to complete
            
            long executionTime = System.currentTimeMillis() - startTime;
            metrics.incrementCounter("gc.intelligent.minor");
            
            return new GCExecutionResult(true, executionTime, "Minor GC completed", "Memory pressure relief");
        } catch (Exception e) {
            return new GCExecutionResult(false, System.currentTimeMillis() - startTime, 
                                       "Minor GC failed: " + e.getMessage(), "Error in execution");
        }
    }
    
    private GCExecutionResult executeFullGC(long startTime) {
        try {
            System.gc();
            forcedCollections.incrementAndGet();
            Thread.sleep(200); // Wait for GC to complete
            
            long executionTime = System.currentTimeMillis() - startTime;
            metrics.incrementCounter("gc.intelligent.full");
            
            return new GCExecutionResult(true, executionTime, "Full GC completed", "Critical memory situation");
        } catch (Exception e) {
            return new GCExecutionResult(false, System.currentTimeMillis() - startTime, 
                                       "Full GC failed: " + e.getMessage(), "Error in execution");
        }
    }
    
    private GCExecutionResult executeTuning(long startTime) {
        try {
            tuningEngine.applyEmergencyTuning();
            
            long executionTime = System.currentTimeMillis() - startTime;
            metrics.incrementCounter("gc.intelligent.tuning");
            
            return new GCExecutionResult(true, executionTime, "GC parameters tuned", "Performance optimization");
        } catch (Exception e) {
            return new GCExecutionResult(false, System.currentTimeMillis() - startTime, 
                                       "Tuning failed: " + e.getMessage(), "Error in tuning");
        }
    }
    
    private void triggerMinorGC() {
        // Allocate and release memory to encourage minor GC
        byte[][] tempArrays = new byte[100][];
        for (int i = 0; i < tempArrays.length; i++) {
            tempArrays[i] = new byte[1024 * 1024]; // 1MB per array
        }
        tempArrays = null; // Release for GC
    }
    
    private double getMemoryUtilization() {
        MemoryUsage heapMemory = memoryMXBean.getHeapMemoryUsage();
        return (double) heapMemory.getUsed() / heapMemory.getMax();
    }
    
    private void generatePerformanceReport() {
        GCStatistics stats = getGCStatistics();
        
        StringBuilder report = new StringBuilder();
        report.append("\n=== Intelligent GC Performance Report ===\n");
        report.append(String.format("Memory Utilization: %.1f%% (%d MB / %d MB)\n",
                     stats.memoryUtilization * 100,
                     stats.heapMemory.getUsed() / 1024 / 1024,
                     stats.heapMemory.getMax() / 1024 / 1024));
        report.append(String.format("Total Collections: %d (Forced: %d)\n", 
                     stats.totalCollections, stats.forcedCollections));
        report.append(String.format("Average Pause Time: %.1fms (Max: %.1fms)\n", 
                     stats.avgPauseTime, stats.maxPauseTime));
        report.append(String.format("GC Frequency: %.1f per minute\n", stats.gcFrequency));
        report.append(String.format("Adaptive Mode: %s\n", stats.adaptiveMode ? "Enabled" : "Disabled"));
        
        GCPerformanceStats perfStats = performanceTracker.getStats();
        if (perfStats.recommendations.length > 0) {
            report.append("Recommendations:\n");
            for (String recommendation : perfStats.recommendations) {
                report.append("  - ").append(recommendation).append('\n');
            }
        }
        
        report.append("=======================================\n");
        LOGGER.info(report.toString());
    }
    
    /**
     * Shutdown the intelligent GC system
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        LOGGER.info("Intelligent garbage collector shut down");
    }
    
    // Inner classes for data structures
    
    public static class GCRecommendation {
        public GCUrgency urgency;
        public GCAction action;
        public String reason;
        
        @Override
        public String toString() {
            return String.format("GCRecommendation{urgency=%s, action=%s, reason='%s'}", 
                               urgency, action, reason);
        }
    }
    
    public static class GCExecutionResult {
        public final boolean success;
        public final long executionTimeMs;
        public final String result;
        public final String context;
        
        public GCExecutionResult(boolean success, long executionTimeMs, String result, String context) {
            this.success = success;
            this.executionTimeMs = executionTimeMs;
            this.result = result;
            this.context = context;
        }
        
        @Override
        public String toString() {
            return String.format("GCExecutionResult{success=%s, time=%dms, result='%s'}", 
                               success, executionTimeMs, result);
        }
    }
    
    public static class GCStatistics {
        public final MemoryUsage heapMemory;
        public final MemoryUsage nonHeapMemory;
        public final long totalCollections;
        public final long totalCollectionTime;
        public final long forcedCollections;
        public final double avgPauseTime;
        public final double maxPauseTime;
        public final double gcFrequency;
        public final double memoryUtilization;
        public final boolean adaptiveMode;
        
        public GCStatistics(MemoryUsage heapMemory, MemoryUsage nonHeapMemory,
                          long totalCollections, long totalCollectionTime, long forcedCollections,
                          double avgPauseTime, double maxPauseTime, double gcFrequency,
                          double memoryUtilization, boolean adaptiveMode) {
            this.heapMemory = heapMemory;
            this.nonHeapMemory = nonHeapMemory;
            this.totalCollections = totalCollections;
            this.totalCollectionTime = totalCollectionTime;
            this.forcedCollections = forcedCollections;
            this.avgPauseTime = avgPauseTime;
            this.maxPauseTime = maxPauseTime;
            this.gcFrequency = gcFrequency;
            this.memoryUtilization = memoryUtilization;
            this.adaptiveMode = adaptiveMode;
        }
    }
    
    public enum GCUrgency {
        LOW, MEDIUM, HIGH, CRITICAL
    }
    
    public enum GCAction {
        MONITOR, MINOR_GC, FULL_GC, TUNE_PARAMETERS
    }
}