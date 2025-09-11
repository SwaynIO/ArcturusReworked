package com.eu.habbo.core.gc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Support components for intelligent garbage collection optimization
 */
public class GCOptimizationComponents {
    
    /**
     * Tracks GC performance metrics over time
     */
    public static class GCPerformanceTracker {
        private static final Logger LOGGER = LoggerFactory.getLogger(GCPerformanceTracker.class);
        
        private final Map<String, GCMetrics> gcMetrics = new ConcurrentHashMap<>();
        private final AtomicLong lastReportTime = new AtomicLong(System.currentTimeMillis());
        
        public void recordGCEvent(String gcName, long totalCollections, long totalTime) {
            GCMetrics metrics = gcMetrics.computeIfAbsent(gcName, k -> new GCMetrics());
            
            long newCollections = totalCollections - metrics.lastCollectionCount;
            long newTime = totalTime - metrics.lastCollectionTime;
            
            if (newCollections > 0) {
                metrics.recordCollection(newCollections, newTime);
                metrics.lastCollectionCount = totalCollections;
                metrics.lastCollectionTime = totalTime;
            }
        }
        
        public GCPerformanceStats getStats() {
            double totalAvgPause = 0.0;
            double maxPause = 0.0;
            double totalFrequency = 0.0;
            int activeCollectors = 0;
            
            List<String> recommendations = new ArrayList<>();
            
            for (Map.Entry<String, GCMetrics> entry : gcMetrics.entrySet()) {
                GCMetrics metrics = entry.getValue();
                if (metrics.collectionCount > 0) {
                    activeCollectors++;
                    totalAvgPause += metrics.getAveragePauseTime();
                    maxPause = Math.max(maxPause, metrics.maxPauseTime);
                    totalFrequency += metrics.getFrequency();
                    
                    // Generate recommendations based on metrics
                    generateRecommendations(entry.getKey(), metrics, recommendations);
                }
            }
            
            double avgPauseTime = activeCollectors > 0 ? totalAvgPause / activeCollectors : 0.0;
            
            return new GCPerformanceStats(avgPauseTime, maxPause, totalFrequency, 
                                        recommendations.toArray(new String[0]));
        }
        
        private void generateRecommendations(String gcName, GCMetrics metrics, List<String> recommendations) {
            if (metrics.getAveragePauseTime() > 500) { // 500ms
                recommendations.add(String.format("High pause time in %s: consider tuning heap size", gcName));
            }
            
            if (metrics.getFrequency() > 20) { // More than 20 per minute
                recommendations.add(String.format("High frequency in %s: increase young generation size", gcName));
            }
            
            if (metrics.maxPauseTime > 2000) { // 2 seconds
                recommendations.add(String.format("Very high max pause in %s: consider concurrent collectors", gcName));
            }
        }
        
        private static class GCMetrics {
            long lastCollectionCount = 0;
            long lastCollectionTime = 0;
            long collectionCount = 0;
            long totalPauseTime = 0;
            double maxPauseTime = 0;
            long lastUpdateTime = System.currentTimeMillis();
            
            void recordCollection(long collections, long pauseTime) {
                collectionCount += collections;
                totalPauseTime += pauseTime;
                
                if (collections > 0) {
                    double avgPauseThisTime = (double) pauseTime / collections;
                    maxPauseTime = Math.max(maxPauseTime, avgPauseThisTime);
                }
                
                lastUpdateTime = System.currentTimeMillis();
            }
            
            double getAveragePauseTime() {
                return collectionCount > 0 ? (double) totalPauseTime / collectionCount : 0.0;
            }
            
            double getFrequency() {
                long timeSpan = System.currentTimeMillis() - (lastUpdateTime - 60000); // Last minute
                return timeSpan > 0 ? (collectionCount * 60000.0) / timeSpan : 0.0;
            }
        }
    }
    
    /**
     * Optimizes memory pool configurations
     */
    public static class MemoryPoolOptimizer {
        private static final Logger LOGGER = LoggerFactory.getLogger(MemoryPoolOptimizer.class);
        
        private final Map<String, MemoryPoolMXBean> memoryPools = new ConcurrentHashMap<>();
        private long lastOptimization = 0;
        
        public MemoryPoolOptimizer() {
            // Initialize memory pools
            for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                memoryPools.put(pool.getName(), pool);
            }
        }
        
        public void optimize(GCPerformanceStats stats) {
            long now = System.currentTimeMillis();
            if (now - lastOptimization < 300000) return; // Don't optimize more than once per 5 minutes
            
            // Analyze memory pools and suggest optimizations
            for (Map.Entry<String, MemoryPoolMXBean> entry : memoryPools.entrySet()) {
                MemoryPoolMXBean pool = entry.getValue();
                String poolName = entry.getKey();
                
                if (pool.getUsage() != null) {
                    double utilization = (double) pool.getUsage().getUsed() / pool.getUsage().getMax();
                    
                    if (utilization > 0.9) {
                        LOGGER.warn("High utilization in memory pool {}: {:.1f}%", poolName, utilization * 100);
                        suggestPoolOptimization(poolName, utilization);
                    }
                }
            }
            
            lastOptimization = now;
        }
        
        private void suggestPoolOptimization(String poolName, double utilization) {
            if (poolName.toLowerCase().contains("eden")) {
                LOGGER.info("Suggestion: Increase Eden space size for better minor GC performance");
            } else if (poolName.toLowerCase().contains("survivor")) {
                LOGGER.info("Suggestion: Adjust survivor ratio for pool {}", poolName);
            } else if (poolName.toLowerCase().contains("old") || poolName.toLowerCase().contains("tenured")) {
                LOGGER.info("Suggestion: Increase old generation size or tune promotion threshold");
            }
        }
        
        public Map<String, Double> getPoolUtilizations() {
            Map<String, Double> utilizations = new HashMap<>();
            
            for (Map.Entry<String, MemoryPoolMXBean> entry : memoryPools.entrySet()) {
                MemoryPoolMXBean pool = entry.getValue();
                if (pool.getUsage() != null && pool.getUsage().getMax() > 0) {
                    double utilization = (double) pool.getUsage().getUsed() / pool.getUsage().getMax();
                    utilizations.put(entry.getKey(), utilization);
                }
            }
            
            return utilizations;
        }
    }
    
    /**
     * Intelligent GC parameter tuning engine
     */
    public static class GCTuningEngine {
        private static final Logger LOGGER = LoggerFactory.getLogger(GCTuningEngine.class);
        
        private final Map<String, Object> currentSettings = new ConcurrentHashMap<>();
        private long lastTuning = 0;
        
        public void applyOptimizations(GCPerformanceStats stats) {
            long now = System.currentTimeMillis();
            if (now - lastTuning < 600000) return; // Don't tune more than once per 10 minutes
            
            // Analyze performance stats and apply optimizations
            if (stats.avgPauseTime > 200) { // High pause times
                applyLowLatencyOptimizations();
            }
            
            if (stats.gcFrequency > 15) { // High GC frequency
                applyThroughputOptimizations();
            }
            
            lastTuning = now;
        }
        
        public void applyEmergencyTuning() {
            LOGGER.warn("Applying emergency GC tuning due to performance issues");
            
            // Emergency tuning suggestions (these would be JVM parameter adjustments)
            logTuningSuggestion("-XX:MaxGCPauseMillis=150", "Reduce maximum GC pause time");
            logTuningSuggestion("-XX:+UseG1GC", "Consider using G1 garbage collector for better latency");
            logTuningSuggestion("-XX:NewRatio=2", "Adjust young to old generation ratio");
        }
        
        private void applyLowLatencyOptimizations() {
            LOGGER.info("Applying low-latency GC optimizations");
            
            logTuningSuggestion("-XX:+UseConcMarkSweepGC", "Use concurrent mark sweep for lower pause times");
            logTuningSuggestion("-XX:+CMSParallelRemarkEnabled", "Enable parallel remark phase");
            logTuningSuggestion("-XX:CMSInitiatingOccupancyFraction=70", "Start CMS earlier to avoid full GC");
        }
        
        private void applyThroughputOptimizations() {
            LOGGER.info("Applying throughput GC optimizations");
            
            logTuningSuggestion("-XX:+UseParallelGC", "Use parallel collector for better throughput");
            logTuningSuggestion("-XX:GCTimeRatio=19", "Optimize for 95% application time, 5% GC time");
            logTuningSuggestion("-Xmx4g", "Consider increasing heap size to reduce GC frequency");
        }
        
        private void logTuningSuggestion(String parameter, String explanation) {
            LOGGER.info("JVM Tuning Suggestion: {} - {}", parameter, explanation);
            currentSettings.put(parameter, explanation);
        }
        
        public Map<String, Object> getCurrentSettings() {
            return new HashMap<>(currentSettings);
        }
    }
    
    /**
     * GC performance statistics container
     */
    public static class GCPerformanceStats {
        public final double avgPauseTime;
        public final double maxPauseTime;
        public final double gcFrequency;
        public final String[] recommendations;
        
        public GCPerformanceStats(double avgPauseTime, double maxPauseTime, 
                                 double gcFrequency, String[] recommendations) {
            this.avgPauseTime = avgPauseTime;
            this.maxPauseTime = maxPauseTime;
            this.gcFrequency = gcFrequency;
            this.recommendations = recommendations;
        }
        
        @Override
        public String toString() {
            return String.format("GCPerformanceStats{avgPause=%.1fms, maxPause=%.1fms, frequency=%.1f/min, recommendations=%d}",
                               avgPauseTime, maxPauseTime, gcFrequency, recommendations.length);
        }
    }
}