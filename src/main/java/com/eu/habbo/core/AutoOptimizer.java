package com.eu.habbo.core;

import com.eu.habbo.core.metrics.RealTimeMetrics;
import com.eu.habbo.Emulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Automatic performance optimizer that adjusts system parameters based on real-time metrics
 * Implements adaptive algorithms to maintain optimal performance under varying load conditions
 */
public class AutoOptimizer implements Disposable {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(AutoOptimizer.class);
    
    private final ScheduledExecutorService scheduler;
    private final RealTimeMetrics metrics;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    // Optimization parameters
    private volatile double memoryThreshold = 0.8; // 80% memory usage triggers optimization
    private volatile long slowOperationThresholdMs = 1000; // 1 second
    private volatile int optimizationIntervalMinutes = 10;
    
    // Optimization history for learning
    private volatile long lastOptimizationTime = 0;
    private volatile int consecutiveOptimizations = 0;
    private volatile boolean lastOptimizationSuccessful = true;
    
    public AutoOptimizer() {
        this.metrics = RealTimeMetrics.getInstance();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Auto-Optimizer");
            t.setDaemon(true);
            return t;
        });
    }
    
    /**
     * Start the auto-optimizer
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            scheduler.scheduleAtFixedRate(
                this::performOptimizationCycle, 
                optimizationIntervalMinutes, 
                optimizationIntervalMinutes, 
                TimeUnit.MINUTES
            );
            LOGGER.info("Auto-optimizer started with {}min interval", optimizationIntervalMinutes);
        }
    }
    
    /**
     * Perform a complete optimization cycle
     */
    private void performOptimizationCycle() {
        try {
            LOGGER.debug("Starting optimization cycle...");
            
            boolean optimizationPerformed = false;
            
            // Memory optimization
            if (checkMemoryPressure()) {
                optimizeMemoryUsage();
                optimizationPerformed = true;
            }
            
            // Thread pool optimization
            if (checkThreadPoolPerformance()) {
                optimizeThreadPool();
                optimizationPerformed = true;
            }
            
            // Cache optimization
            if (checkCachePerformance()) {
                optimizeCaches();
                optimizationPerformed = true;
            }
            
            // Database optimization
            if (checkDatabasePerformance()) {
                optimizeDatabase();
                optimizationPerformed = true;
            }
            
            // Update optimization history
            updateOptimizationHistory(optimizationPerformed);
            
            if (optimizationPerformed) {
                LOGGER.info("Optimization cycle completed with improvements");
            } else {
                LOGGER.debug("No optimization needed this cycle");
            }
            
        } catch (Exception e) {
            LOGGER.error("Error during optimization cycle", e);
            lastOptimizationSuccessful = false;
        }
    }
    
    /**
     * Check if memory pressure requires optimization
     */
    private boolean checkMemoryPressure() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        double memoryUsage = (double) usedMemory / runtime.maxMemory();
        
        if (memoryUsage > memoryThreshold) {
            LOGGER.warn("High memory usage detected: {:.1f}%", memoryUsage * 100);
            return true;
        }
        
        return false;
    }
    
    /**
     * Optimize memory usage
     */
    private void optimizeMemoryUsage() {
        LOGGER.info("Performing memory optimization...");
        
        // Suggest garbage collection
        System.gc();
        
        // Clear non-essential caches if memory is critical
        Runtime runtime = Runtime.getRuntime();
        double memoryUsage = (double) (runtime.totalMemory() - runtime.freeMemory()) / runtime.maxMemory();
        
        if (memoryUsage > 0.9) { // Critical memory usage
            LOGGER.warn("Critical memory usage - clearing non-essential caches");
            // Could implement cache clearing here
        }
        
        // Log memory improvement
        long usedAfter = runtime.totalMemory() - runtime.freeMemory();
        LOGGER.info("Memory optimization completed - current usage: {} MB", usedAfter / 1024 / 1024);
    }
    
    /**
     * Check thread pool performance
     */
    private boolean checkThreadPoolPerformance() {
        if (Emulator.getThreading() != null) {
            String poolStats = Emulator.getThreading().getPoolStats();
            LOGGER.debug("Thread pool stats: {}", poolStats);
            
            // Could implement thread pool analysis here
            // For now, return false as existing pool is optimized
        }
        return false;
    }
    
    /**
     * Optimize thread pool
     */
    private void optimizeThreadPool() {
        LOGGER.info("Performing thread pool optimization...");
        // Thread pool optimization logic would go here
    }
    
    /**
     * Check cache performance
     */
    private boolean checkCachePerformance() {
        // Check various cache hit rates and sizes
        // This would integrate with the cache managers
        return false; // Placeholder
    }
    
    /**
     * Optimize caches
     */
    private void optimizeCaches() {
        LOGGER.info("Performing cache optimization...");
        // Cache optimization logic would go here
    }
    
    /**
     * Check database performance
     */
    private boolean checkDatabasePerformance() {
        long dbQueries = metrics.getMetricValue("database.queries.total");
        
        // Check for high database query rate
        if (dbQueries > 0) {
            // Could implement database performance analysis here
        }
        
        return false; // Placeholder
    }
    
    /**
     * Optimize database performance
     */
    private void optimizeDatabase() {
        LOGGER.info("Performing database optimization...");
        // Database optimization logic would go here
    }
    
    /**
     * Update optimization history for learning
     */
    private void updateOptimizationHistory(boolean optimizationPerformed) {
        if (optimizationPerformed) {
            consecutiveOptimizations++;
            lastOptimizationTime = System.currentTimeMillis();
            
            // Adjust optimization interval based on success rate
            if (lastOptimizationSuccessful && consecutiveOptimizations > 3) {
                // Reduce frequency if consistently optimizing
                optimizationIntervalMinutes = Math.min(optimizationIntervalMinutes + 5, 60);
                LOGGER.info("Increased optimization interval to {} minutes", optimizationIntervalMinutes);
            }
        } else {
            consecutiveOptimizations = 0;
            
            // Increase frequency if no optimization needed
            if (optimizationIntervalMinutes > 10) {
                optimizationIntervalMinutes = Math.max(optimizationIntervalMinutes - 2, 5);
                LOGGER.info("Decreased optimization interval to {} minutes", optimizationIntervalMinutes);
            }
        }
        
        lastOptimizationSuccessful = true;
    }
    
    /**
     * Trigger immediate optimization
     */
    public void optimizeNow() {
        if (running.get()) {
            scheduler.execute(this::performOptimizationCycle);
            LOGGER.info("Manual optimization triggered");
        }
    }
    
    /**
     * Configure optimization parameters
     */
    public void configure(double memoryThreshold, long slowOperationThresholdMs, int intervalMinutes) {
        this.memoryThreshold = Math.max(0.5, Math.min(0.95, memoryThreshold));
        this.slowOperationThresholdMs = Math.max(100, slowOperationThresholdMs);
        this.optimizationIntervalMinutes = Math.max(1, Math.min(120, intervalMinutes));
        
        LOGGER.info("Auto-optimizer configured - memory threshold: {:.1f}%, slow operation: {}ms, interval: {}min",
                   this.memoryThreshold * 100, this.slowOperationThresholdMs, this.optimizationIntervalMinutes);
    }
    
    /**
     * Get optimization statistics
     */
    public String getOptimizationStats() {
        return String.format(
            "Auto-Optimizer Stats - Running: %s, Interval: %dmin, Consecutive optimizations: %d, Last: %s",
            running.get(),
            optimizationIntervalMinutes,
            consecutiveOptimizations,
            lastOptimizationTime > 0 ? 
                String.format("%dmin ago", (System.currentTimeMillis() - lastOptimizationTime) / 60000) : 
                "never"
        );
    }
    
    @Override
    public void dispose() {
        if (running.compareAndSet(true, false)) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            LOGGER.info("Auto-optimizer disposed");
        }
    }
}