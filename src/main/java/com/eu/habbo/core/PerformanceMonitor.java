package com.eu.habbo.core;

import com.eu.habbo.Emulator;
import com.eu.habbo.util.PerformanceUtils;
import com.eu.habbo.habbohotel.users.UserDataCache;
import com.eu.habbo.habbohotel.rooms.RoomCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Performance monitoring and optimization service for Arcturus Morningstar Reworked
 * Monitors system performance and provides optimization recommendations
 */
public class PerformanceMonitor implements Disposable {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(PerformanceMonitor.class);
    
    private final ScheduledExecutorService scheduler;
    private final MemoryMXBean memoryBean;
    private long lastGcCollections = 0;
    private long lastGcTime = 0;
    private volatile boolean running = false;
    
    public PerformanceMonitor() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Performance-Monitor");
            t.setDaemon(true);
            return t;
        });
        this.memoryBean = ManagementFactory.getMemoryMXBean();
    }
    
    /**
     * Start performance monitoring
     */
    public void start() {
        if (!running) {
            running = true;
            
            // Monitor every 30 seconds
            scheduler.scheduleAtFixedRate(this::collectMetrics, 30, 30, TimeUnit.SECONDS);
            
            // Report every 5 minutes
            scheduler.scheduleAtFixedRate(this::reportPerformance, 300, 300, TimeUnit.SECONDS);
            
            // Cleanup every 10 minutes
            scheduler.scheduleAtFixedRate(this::performCleanup, 600, 600, TimeUnit.SECONDS);
            
            LOGGER.info("Performance monitoring started");
        }
    }
    
    /**
     * Collect performance metrics
     */
    private void collectMetrics() {
        try {
            // Memory metrics
            MemoryUsage heapMemory = memoryBean.getHeapMemoryUsage();
            MemoryUsage nonHeapMemory = memoryBean.getNonHeapMemoryUsage();
            
            // GC metrics
            long totalCollections = 0;
            long totalCollectionTime = 0;
            for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
                totalCollections += gcBean.getCollectionCount();
                totalCollectionTime += gcBean.getCollectionTime();
            }
            
            // Check for performance issues
            checkMemoryPressure(heapMemory);
            checkGarbageCollection(totalCollections, totalCollectionTime);
            
            lastGcCollections = totalCollections;
            lastGcTime = totalCollectionTime;
            
        } catch (Exception e) {
            LOGGER.error("Error collecting performance metrics", e);
        }
    }
    
    /**
     * Check for memory pressure
     */
    private void checkMemoryPressure(MemoryUsage heapMemory) {
        double memoryUsagePercent = (double) heapMemory.getUsed() / heapMemory.getMax() * 100;
        
        if (memoryUsagePercent > 85) {
            LOGGER.warn("High memory usage detected: {:.2f}%", memoryUsagePercent);
            
            if (memoryUsagePercent > 95) {
                LOGGER.error("Critical memory usage: {:.2f}% - Consider increasing heap size", 
                           memoryUsagePercent);
                // Trigger emergency cleanup
                performEmergencyCleanup();
            }
        }
    }
    
    /**
     * Check garbage collection performance
     */
    private void checkGarbageCollection(long totalCollections, long totalTime) {
        long newCollections = totalCollections - lastGcCollections;
        long newTime = totalTime - lastGcTime;
        
        if (newCollections > 0) {
            double avgGcTime = (double) newTime / newCollections;
            
            if (avgGcTime > 100) { // More than 100ms average GC time
                LOGGER.warn("High GC pause time detected: {:.2f}ms average over {} collections", 
                           avgGcTime, newCollections);
            }
            
            if (newCollections > 10) { // More than 10 GC cycles in 30 seconds
                LOGGER.warn("High GC frequency detected: {} collections in 30 seconds", 
                           newCollections);
            }
        }
    }
    
    /**
     * Report performance summary
     */
    private void reportPerformance() {
        try {
            StringBuilder report = new StringBuilder();
            report.append("\n=== Performance Report ===\n");
            
            // Memory information
            MemoryUsage heap = memoryBean.getHeapMemoryUsage();
            report.append(String.format("Heap Memory: %d MB / %d MB (%.1f%%)\n",
                heap.getUsed() / 1024 / 1024,
                heap.getMax() / 1024 / 1024,
                (double) heap.getUsed() / heap.getMax() * 100));
            
            // Performance counters
            report.append(PerformanceUtils.getPerformanceSummary());
            
            // Cache statistics
            report.append("Cache Stats:\n");
            report.append("  ").append(UserDataCache.getCacheStats()).append('\n');
            report.append("  ").append(RoomCache.getCacheStats()).append('\n');
            
            // Thread pool information
            if (Emulator.getThreading() != null) {
                report.append(String.format("Thread Pool: %d threads\n", 
                    Emulator.getThreading().threads));
            }
            
            report.append("========================\n");
            
            LOGGER.info(report.toString());
            
        } catch (Exception e) {
            LOGGER.error("Error generating performance report", e);
        }
    }
    
    /**
     * Perform regular cleanup
     */
    private void performCleanup() {
        try {
            // Reset performance counters periodically
            PerformanceUtils.resetCounters();
            
            LOGGER.debug("Performed scheduled cleanup");
        } catch (Exception e) {
            LOGGER.error("Error during scheduled cleanup", e);
        }
    }
    
    /**
     * Perform emergency cleanup when memory is critical
     */
    private void performEmergencyCleanup() {
        try {
            LOGGER.warn("Performing emergency cleanup due to high memory usage");
            
            // Suggest garbage collection
            System.gc();
            
            // Could also clear non-essential caches here if needed
            
        } catch (Exception e) {
            LOGGER.error("Error during emergency cleanup", e);
        }
    }
    
    /**
     * Get current memory usage percentage
     */
    public double getMemoryUsagePercent() {
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        return (double) heap.getUsed() / heap.getMax() * 100;
    }
    
    /**
     * Get JVM uptime in milliseconds
     */
    public long getUptime() {
        return ManagementFactory.getRuntimeMXBean().getUptime();
    }
    
    @Override
    public void dispose() {
        if (running) {
            running = false;
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            LOGGER.info("Performance monitor disposed");
        }
    }
}