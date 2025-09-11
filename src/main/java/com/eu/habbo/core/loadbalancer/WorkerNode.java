package com.eu.habbo.core.loadbalancer;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Represents a worker node in the load balancer
 */
public class WorkerNode {
    
    private final String id;
    private final int capacity;
    private final WorkerType type;
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicLong totalTasks = new AtomicLong(0);
    private final AtomicLong totalResponseTime = new AtomicLong(0);
    private final DoubleAdder exponentialAvgResponseTime = new DoubleAdder();
    private volatile boolean healthy = true;
    private volatile long lastTaskTime = System.currentTimeMillis();
    
    // Exponential moving average parameters
    private static final double ALPHA = 0.1;
    private volatile double currentAvgResponseTime = 0.0;
    
    public WorkerNode(String id, int capacity, WorkerType type) {
        this.id = id;
        this.capacity = capacity;
        this.type = type;
    }
    
    /**
     * Assign a task to this worker
     */
    public void assignTask() {
        activeConnections.incrementAndGet();
        lastTaskTime = System.currentTimeMillis();
    }
    
    /**
     * Complete a task and update statistics
     */
    public void completeTask(long executionTimeMs) {
        activeConnections.decrementAndGet();
        totalTasks.incrementAndGet();
        totalResponseTime.addAndGet(executionTimeMs);
        
        // Update exponential moving average
        updateExponentialAverage(executionTimeMs);
    }
    
    private void updateExponentialAverage(long responseTime) {
        if (currentAvgResponseTime == 0.0) {
            currentAvgResponseTime = responseTime;
        } else {
            currentAvgResponseTime = ALPHA * responseTime + (1 - ALPHA) * currentAvgResponseTime;
        }
    }
    
    /**
     * Get current load as percentage (0.0 to 1.0)
     */
    public double getCurrentLoad() {
        if (capacity <= 0) return 1.0;
        return Math.min(1.0, (double) activeConnections.get() / capacity);
    }
    
    /**
     * Get average response time
     */
    public double getAverageResponseTime() {
        return currentAvgResponseTime;
    }
    
    /**
     * Check if worker has been idle
     */
    public boolean isIdle(long idleThresholdMs) {
        return System.currentTimeMillis() - lastTaskTime > idleThresholdMs;
    }
    
    // Getters
    public String getId() { return id; }
    public int getCapacity() { return capacity; }
    public WorkerType getType() { return type; }
    public int getActiveConnections() { return activeConnections.get(); }
    public long getTotalTasks() { return totalTasks.get(); }
    public boolean isHealthy() { return healthy; }
    
    public void setHealthy(boolean healthy) { 
        this.healthy = healthy; 
    }
    
    @Override
    public String toString() {
        return String.format("WorkerNode{id='%s', type=%s, capacity=%d, load=%.1f%%, healthy=%s, avgResponseTime=%.1fms}",
                           id, type, capacity, getCurrentLoad() * 100, healthy, getAverageResponseTime());
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        WorkerNode that = (WorkerNode) obj;
        return id.equals(that.id);
    }
    
    @Override
    public int hashCode() {
        return id.hashCode();
    }
}

/**
 * Worker types for specialized task handling
 */
enum WorkerType {
    GENERAL,      // General purpose worker
    DATABASE,     // Database operations
    FILE_IO,      // File I/O operations  
    NETWORK,      // Network operations
    COMPUTATION   // CPU-intensive tasks
}

/**
 * Task priority levels
 */
enum TaskPriority {
    LOW,
    NORMAL, 
    HIGH,
    CRITICAL
}

/**
 * Load balancing strategies
 */
enum LoadBalancingStrategy {
    ROUND_ROBIN,
    WEIGHTED_ROUND_ROBIN,
    LEAST_CONNECTIONS,
    LEAST_RESPONSE_TIME,
    ADAPTIVE
}

/**
 * Load balancer statistics
 */
class LoadBalancerStats {
    public final int totalWorkers;
    public final int healthyWorkers;
    public final LoadBalancingStrategy strategy;
    public final double avgLoad;
    public final double avgResponseTime;
    
    public LoadBalancerStats(int totalWorkers, int healthyWorkers, 
                           LoadBalancingStrategy strategy, double avgLoad, double avgResponseTime) {
        this.totalWorkers = totalWorkers;
        this.healthyWorkers = healthyWorkers;
        this.strategy = strategy;
        this.avgLoad = avgLoad;
        this.avgResponseTime = avgResponseTime;
    }
    
    @Override
    public String toString() {
        return String.format("LoadBalancerStats{workers=%d/%d healthy, strategy=%s, avgLoad=%.1f%%, avgResponseTime=%.1fms}",
                           healthyWorkers, totalWorkers, strategy, avgLoad * 100, avgResponseTime);
    }
}