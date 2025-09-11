package com.eu.habbo.core.loadbalancer;

import com.eu.habbo.core.metrics.RealTimeMetrics;
import com.eu.habbo.util.collections.OptimizedConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Intelligent load balancer for Arcturus Morningstar Reworked
 * Implements advanced load balancing algorithms with adaptive resource allocation
 */
public class IntelligentLoadBalancer {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(IntelligentLoadBalancer.class);
    
    private final OptimizedConcurrentMap<String, WorkerNode> workers;
    private final ScheduledExecutorService scheduler;
    private final RealTimeMetrics metrics;
    
    // Load balancing strategies
    private volatile LoadBalancingStrategy currentStrategy = LoadBalancingStrategy.WEIGHTED_ROUND_ROBIN;
    private final AtomicInteger roundRobinCounter = new AtomicInteger(0);
    private final AtomicLong lastRebalance = new AtomicLong(0);
    
    // Performance thresholds
    private static final double HIGH_LOAD_THRESHOLD = 0.8;
    private static final double LOW_LOAD_THRESHOLD = 0.3;
    private static final long REBALANCE_INTERVAL_MS = 30000; // 30 seconds
    
    // Circuit breaker for unhealthy nodes
    private final OptimizedConcurrentMap<String, CircuitBreaker> circuitBreakers;
    
    public IntelligentLoadBalancer() {
        this.workers = new OptimizedConcurrentMap<>("LoadBalancer-Workers", 64);
        this.circuitBreakers = new OptimizedConcurrentMap<>("LoadBalancer-CircuitBreakers", 64);
        this.metrics = RealTimeMetrics.getInstance();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LoadBalancer-Monitor");
            t.setDaemon(true);
            return t;
        });
        
        startMonitoring();
    }
    
    /**
     * Register a worker node
     */
    public void registerWorker(String workerId, int capacity, WorkerType type) {
        WorkerNode worker = new WorkerNode(workerId, capacity, type);
        workers.put(workerId, worker);
        circuitBreakers.put(workerId, new CircuitBreaker(workerId));
        
        LOGGER.info("Registered worker {} with capacity {} and type {}", workerId, capacity, type);
        metrics.incrementCounter("loadbalancer.workers.registered");
    }
    
    /**
     * Unregister a worker node
     */
    public void unregisterWorker(String workerId) {
        WorkerNode removed = workers.remove(workerId);
        circuitBreakers.remove(workerId);
        
        if (removed != null) {
            LOGGER.info("Unregistered worker {}", workerId);
            metrics.incrementCounter("loadbalancer.workers.unregistered");
        }
    }
    
    /**
     * Select the best worker for a task
     */
    public WorkerNode selectWorker(TaskPriority priority, WorkerType preferredType) {
        List<WorkerNode> availableWorkers = getHealthyWorkers(preferredType);
        
        if (availableWorkers.isEmpty()) {
            metrics.incrementCounter("loadbalancer.selection.no_workers");
            return null;
        }
        
        WorkerNode selected = null;
        switch (currentStrategy) {
            case ROUND_ROBIN:
                selected = selectRoundRobin(availableWorkers);
                break;
            case WEIGHTED_ROUND_ROBIN:
                selected = selectWeightedRoundRobin(availableWorkers);
                break;
            case LEAST_CONNECTIONS:
                selected = selectLeastConnections(availableWorkers);
                break;
            case LEAST_RESPONSE_TIME:
                selected = selectLeastResponseTime(availableWorkers);
                break;
            case ADAPTIVE:
                selected = selectAdaptive(availableWorkers, priority);
                break;
        }
        
        if (selected != null) {
            selected.assignTask();
            metrics.incrementCounter("loadbalancer.selection.success");
            metrics.setGauge("loadbalancer.selected_worker.load", (long)(selected.getCurrentLoad() * 100));
        }
        
        return selected;
    }
    
    /**
     * Get healthy workers optionally filtered by type
     */
    private List<WorkerNode> getHealthyWorkers(WorkerType preferredType) {
        return workers.values().stream()
                .filter(worker -> {
                    CircuitBreaker cb = circuitBreakers.get(worker.getId());
                    return cb != null && cb.canExecute() && worker.isHealthy();
                })
                .filter(worker -> preferredType == null || worker.getType() == preferredType)
                .sorted((a, b) -> Double.compare(a.getCurrentLoad(), b.getCurrentLoad()))
                .collect(Collectors.toList());
    }
    
    /**
     * Simple round robin selection
     */
    private WorkerNode selectRoundRobin(List<WorkerNode> workers) {
        if (workers.isEmpty()) return null;
        int index = roundRobinCounter.getAndIncrement() % workers.size();
        return workers.get(index);
    }
    
    /**
     * Weighted round robin based on capacity
     */
    private WorkerNode selectWeightedRoundRobin(List<WorkerNode> workers) {
        if (workers.isEmpty()) return null;
        
        int totalWeight = workers.stream().mapToInt(WorkerNode::getCapacity).sum();
        int target = roundRobinCounter.getAndIncrement() % totalWeight;
        
        int currentWeight = 0;
        for (WorkerNode worker : workers) {
            currentWeight += worker.getCapacity();
            if (target < currentWeight) {
                return worker;
            }
        }
        
        return workers.get(0); // Fallback
    }
    
    /**
     * Select worker with least active connections
     */
    private WorkerNode selectLeastConnections(List<WorkerNode> workers) {
        return workers.stream()
                .min(Comparator.comparingInt(WorkerNode::getActiveConnections))
                .orElse(null);
    }
    
    /**
     * Select worker with lowest average response time
     */
    private WorkerNode selectLeastResponseTime(List<WorkerNode> workers) {
        return workers.stream()
                .min(Comparator.comparingDouble(WorkerNode::getAverageResponseTime))
                .orElse(null);
    }
    
    /**
     * Adaptive selection based on multiple factors
     */
    private WorkerNode selectAdaptive(List<WorkerNode> workers, TaskPriority priority) {
        return workers.stream()
                .min((a, b) -> {
                    double scoreA = calculateWorkerScore(a, priority);
                    double scoreB = calculateWorkerScore(b, priority);
                    return Double.compare(scoreA, scoreB);
                })
                .orElse(null);
    }
    
    /**
     * Calculate worker score for adaptive selection
     */
    private double calculateWorkerScore(WorkerNode worker, TaskPriority priority) {
        double loadWeight = 0.4;
        double responseTimeWeight = 0.3;
        double capacityWeight = 0.2;
        double priorityWeight = 0.1;
        
        double loadScore = worker.getCurrentLoad();
        double responseScore = worker.getAverageResponseTime() / 1000.0; // Normalize to seconds
        double capacityScore = 1.0 - (worker.getCapacity() / 100.0); // Invert so higher capacity = lower score
        double priorityScore = priority == TaskPriority.HIGH ? 0.0 : 1.0;
        
        return (loadScore * loadWeight) + 
               (responseScore * responseTimeWeight) + 
               (capacityScore * capacityWeight) + 
               (priorityScore * priorityWeight);
    }
    
    /**
     * Report task completion to update worker statistics
     */
    public void reportTaskCompletion(String workerId, long executionTimeMs, boolean success) {
        WorkerNode worker = workers.get(workerId);
        CircuitBreaker cb = circuitBreakers.get(workerId);
        
        if (worker != null) {
            worker.completeTask(executionTimeMs);
            
            if (cb != null) {
                if (success) {
                    cb.recordSuccess();
                } else {
                    cb.recordFailure();
                }
            }
            
            metrics.recordTime("loadbalancer.task.execution", executionTimeMs * 1_000_000); // Convert to nanos
        }
    }
    
    /**
     * Start monitoring and auto-rebalancing
     */
    private void startMonitoring() {
        // Monitor worker health every 10 seconds
        scheduler.scheduleAtFixedRate(() -> {
            try {
                monitorWorkerHealth();
            } catch (Exception e) {
                LOGGER.error("Error monitoring worker health", e);
            }
        }, 10, 10, TimeUnit.SECONDS);
        
        // Adaptive strategy adjustment every 30 seconds
        scheduler.scheduleAtFixedRate(() -> {
            try {
                adjustLoadBalancingStrategy();
            } catch (Exception e) {
                LOGGER.error("Error adjusting load balancing strategy", e);
            }
        }, 30, 30, TimeUnit.SECONDS);
        
        // Generate performance report every 5 minutes
        scheduler.scheduleAtFixedRate(() -> {
            try {
                generatePerformanceReport();
            } catch (Exception e) {
                LOGGER.error("Error generating performance report", e);
            }
        }, 300, 300, TimeUnit.SECONDS);
    }
    
    /**
     * Monitor worker health and update circuit breakers
     */
    private void monitorWorkerHealth() {
        for (WorkerNode worker : workers.values()) {
            double currentLoad = worker.getCurrentLoad();
            CircuitBreaker cb = circuitBreakers.get(worker.getId());
            
            // Update worker health status
            if (currentLoad > HIGH_LOAD_THRESHOLD) {
                worker.setHealthy(false);
                metrics.incrementCounter("loadbalancer.worker.overloaded");
            } else if (currentLoad < LOW_LOAD_THRESHOLD) {
                worker.setHealthy(true);
            }
            
            // Update circuit breaker state
            if (cb != null) {
                if (worker.getAverageResponseTime() > 5000) { // 5 seconds
                    cb.recordFailure();
                }
            }
        }
    }
    
    /**
     * Adaptively adjust load balancing strategy based on performance
     */
    private void adjustLoadBalancingStrategy() {
        long now = System.currentTimeMillis();
        if (now - lastRebalance.get() < REBALANCE_INTERVAL_MS) {
            return;
        }
        
        // Analyze current performance
        double avgResponseTime = workers.values().stream()
                .mapToDouble(WorkerNode::getAverageResponseTime)
                .average().orElse(0.0);
        
        double avgLoad = workers.values().stream()
                .mapToDouble(WorkerNode::getCurrentLoad)
                .average().orElse(0.0);
        
        LoadBalancingStrategy newStrategy = currentStrategy;
        
        if (avgResponseTime > 3000 && avgLoad > HIGH_LOAD_THRESHOLD) {
            // High latency and load - use least response time
            newStrategy = LoadBalancingStrategy.LEAST_RESPONSE_TIME;
        } else if (avgLoad > HIGH_LOAD_THRESHOLD) {
            // High load - use least connections
            newStrategy = LoadBalancingStrategy.LEAST_CONNECTIONS;
        } else if (avgLoad < LOW_LOAD_THRESHOLD) {
            // Low load - use weighted round robin for even distribution
            newStrategy = LoadBalancingStrategy.WEIGHTED_ROUND_ROBIN;
        } else {
            // Moderate load - use adaptive algorithm
            newStrategy = LoadBalancingStrategy.ADAPTIVE;
        }
        
        if (newStrategy != currentStrategy) {
            LOGGER.info("Switching load balancing strategy from {} to {} (avgLoad={:.2f}, avgResponseTime={:.0f}ms)",
                       currentStrategy, newStrategy, avgLoad, avgResponseTime);
            currentStrategy = newStrategy;
            metrics.incrementCounter("loadbalancer.strategy.changed");
        }
        
        lastRebalance.set(now);
    }
    
    /**
     * Generate comprehensive performance report
     */
    private void generatePerformanceReport() {
        StringBuilder report = new StringBuilder();
        report.append("\n=== Load Balancer Performance Report ===\n");
        
        report.append(String.format("Current Strategy: %s\n", currentStrategy));
        report.append(String.format("Total Workers: %d\n", workers.size()));
        
        long healthyWorkers = workers.values().stream().mapToLong(w -> w.isHealthy() ? 1 : 0).sum();
        report.append(String.format("Healthy Workers: %d\n", healthyWorkers));
        
        if (!workers.isEmpty()) {
            double avgLoad = workers.values().stream().mapToDouble(WorkerNode::getCurrentLoad).average().orElse(0.0);
            double avgResponseTime = workers.values().stream().mapToDouble(WorkerNode::getAverageResponseTime).average().orElse(0.0);
            
            report.append(String.format("Average Load: %.2f%%\n", avgLoad * 100));
            report.append(String.format("Average Response Time: %.0fms\n", avgResponseTime));
            
            // Top performers
            report.append("\nTop Performing Workers:\n");
            workers.values().stream()
                   .sorted(Comparator.comparingDouble(WorkerNode::getCurrentLoad))
                   .limit(5)
                   .forEach(worker -> {
                       report.append(String.format("  %s: %.1f%% load, %.0fms avg response\n",
                           worker.getId(), worker.getCurrentLoad() * 100, worker.getAverageResponseTime()));
                   });
        }
        
        report.append("===================================\n");
        LOGGER.info(report.toString());
    }
    
    /**
     * Get load balancer statistics
     */
    public LoadBalancerStats getStats() {
        return new LoadBalancerStats(
            workers.size(),
            (int) workers.values().stream().mapToLong(w -> w.isHealthy() ? 1 : 0).sum(),
            currentStrategy,
            workers.values().stream().mapToDouble(WorkerNode::getCurrentLoad).average().orElse(0.0),
            workers.values().stream().mapToDouble(WorkerNode::getAverageResponseTime).average().orElse(0.0)
        );
    }
    
    /**
     * Shutdown the load balancer
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
        LOGGER.info("Intelligent load balancer shut down");
    }
}