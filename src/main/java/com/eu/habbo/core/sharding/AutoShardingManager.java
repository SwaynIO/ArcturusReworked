package com.eu.habbo.core.sharding;

import com.eu.habbo.core.metrics.RealTimeMetrics;
import com.eu.habbo.util.collections.OptimizedConcurrentMap;
import com.eu.habbo.core.ai.LoadPredictionEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Automatic sharding manager for Arcturus Morningstar Reworked
 * Provides intelligent data partitioning and load distribution across multiple nodes
 */
public class AutoShardingManager<K, V> {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(AutoShardingManager.class);
    
    // Sharding configuration
    private final String shardName;
    private final OptimizedConcurrentMap<Integer, ShardNode<K, V>> shards;
    private final AtomicInteger shardCount = new AtomicInteger(1);
    private final Function<K, Integer> hashFunction;
    
    // Load balancing and monitoring
    private final ShardLoadBalancer<K, V> loadBalancer;
    private final ShardMonitor shardMonitor;
    private final RealTimeMetrics metrics;
    private final ScheduledExecutorService scheduler;
    
    // Auto-scaling parameters
    private volatile double scaleUpThreshold = 0.8;   // 80% load triggers scale up
    private volatile double scaleDownThreshold = 0.3; // 30% load triggers scale down
    private volatile int minShards = 1;
    private volatile int maxShards = 32;
    private volatile boolean autoScalingEnabled = true;
    
    // Rebalancing state
    private final AtomicLong lastRebalance = new AtomicLong(0);
    private final AtomicLong rebalanceCount = new AtomicLong(0);
    private volatile boolean rebalanceInProgress = false;
    
    public AutoShardingManager(String shardName, Function<K, Integer> hashFunction) {
        this.shardName = shardName;
        this.hashFunction = hashFunction != null ? hashFunction : this::defaultHashFunction;
        this.shards = new OptimizedConcurrentMap<>("Shards-" + shardName, 64);
        this.loadBalancer = new ShardLoadBalancer<>(this);
        this.shardMonitor = new ShardMonitor(this);
        this.metrics = RealTimeMetrics.getInstance();
        
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "AutoShard-" + shardName + "-" + r.hashCode());
            t.setDaemon(true);
            return t;
        });
        
        // Initialize with one shard
        createInitialShard();
        startMonitoring();
        
        LOGGER.info("Auto-sharding manager '{}' initialized with hash function", shardName);
    }
    
    /**
     * Get value from appropriate shard
     */
    public V get(K key) {
        int shardId = getShardId(key);
        ShardNode<K, V> shard = shards.get(shardId);
        
        if (shard != null) {
            V value = shard.get(key);
            shard.recordRead();
            metrics.incrementCounter("sharding." + shardName + ".reads");
            return value;
        }
        
        // Fallback to any available shard if target shard not found
        return findValueInAnyShaard(key);
    }
    
    /**
     * Put value in appropriate shard
     */
    public void put(K key, V value) {
        int shardId = getShardId(key);
        ShardNode<K, V> shard = getOrCreateShard(shardId);
        
        shard.put(key, value);
        shard.recordWrite();
        metrics.incrementCounter("sharding." + shardName + ".writes");
        
        // Check if this shard needs to be split
        if (autoScalingEnabled && shard.shouldSplit()) {
            scheduleShardSplit(shardId);
        }
    }
    
    /**
     * Remove value from appropriate shard
     */
    public V remove(K key) {
        int shardId = getShardId(key);
        ShardNode<K, V> shard = shards.get(shardId);
        
        if (shard != null) {
            V removed = shard.remove(key);
            if (removed != null) {
                shard.recordWrite();
                metrics.incrementCounter("sharding." + shardName + ".removes");
            }
            return removed;
        }
        
        return null;
    }
    
    /**
     * Get all values across all shards
     */
    public Collection<V> getAllValues() {
        List<V> allValues = new ArrayList<>();
        
        for (ShardNode<K, V> shard : shards.values()) {
            allValues.addAll(shard.getAllValues());
        }
        
        return allValues;
    }
    
    /**
     * Get comprehensive sharding statistics
     */
    public ShardingStats getShardingStats() {
        int totalShards = shards.size();
        long totalItems = 0;
        long totalReads = 0;
        long totalWrites = 0;
        double avgLoad = 0.0;
        double maxLoad = 0.0;
        
        List<ShardStats> shardStats = new ArrayList<>();
        
        for (ShardNode<K, V> shard : shards.values()) {
            ShardStats stats = shard.getStats();
            shardStats.add(stats);
            
            totalItems += stats.itemCount;
            totalReads += stats.readCount;
            totalWrites += stats.writeCount;
            avgLoad += stats.loadFactor;
            maxLoad = Math.max(maxLoad, stats.loadFactor);
        }
        
        avgLoad = totalShards > 0 ? avgLoad / totalShards : 0.0;
        
        return new ShardingStats(
            shardName, totalShards, totalItems, totalReads, totalWrites,
            avgLoad, maxLoad, rebalanceCount.get(),
            System.currentTimeMillis() - lastRebalance.get(),
            autoScalingEnabled, shardStats
        );
    }
    
    /**
     * Force rebalancing of shards
     */
    public CompletableFuture<RebalanceResult> rebalanceShards() {
        if (rebalanceInProgress) {
            return CompletableFuture.completedFuture(
                new RebalanceResult(false, "Rebalance already in progress", 0, 0));
        }
        
        return CompletableFuture.supplyAsync(() -> {
            rebalanceInProgress = true;
            long startTime = System.currentTimeMillis();
            
            try {
                return performRebalance(startTime);
            } finally {
                rebalanceInProgress = false;
            }
        }, scheduler);
    }
    
    /**
     * Scale shards up or down based on load
     */
    public ScaleResult scaleShards(ScaleDirection direction, int count) {
        if (rebalanceInProgress) {
            return new ScaleResult(false, "Cannot scale during rebalance", shards.size());
        }
        
        int currentCount = shards.size();
        int targetCount = direction == ScaleDirection.UP ? 
            Math.min(maxShards, currentCount + count) :
            Math.max(minShards, currentCount - count);
        
        if (targetCount == currentCount) {
            return new ScaleResult(false, "Already at optimal shard count", currentCount);
        }
        
        try {
            if (direction == ScaleDirection.UP) {
                return scaleUp(targetCount - currentCount);
            } else {
                return scaleDown(currentCount - targetCount);
            }
        } catch (Exception e) {
            LOGGER.error("Error scaling shards", e);
            return new ScaleResult(false, "Scale operation failed: " + e.getMessage(), currentCount);
        }
    }
    
    /**
     * Configure auto-scaling parameters
     */
    public void configureAutoScaling(boolean enabled, double scaleUpThreshold, double scaleDownThreshold,
                                   int minShards, int maxShards) {
        this.autoScalingEnabled = enabled;
        this.scaleUpThreshold = Math.max(0.5, Math.min(0.95, scaleUpThreshold));
        this.scaleDownThreshold = Math.max(0.1, Math.min(0.8, scaleDownThreshold));
        this.minShards = Math.max(1, minShards);
        this.maxShards = Math.max(minShards, maxShards);
        
        LOGGER.info("Auto-scaling configured for '{}' - enabled: {}, thresholds: {:.1f}%/{:.1f}%, range: {}-{}", 
                   shardName, enabled, scaleUpThreshold * 100, scaleDownThreshold * 100, minShards, maxShards);
    }
    
    // Private helper methods
    
    private void createInitialShard() {
        ShardNode<K, V> initialShard = new ShardNode<>(0, shardName + "-0");
        shards.put(0, initialShard);
        LOGGER.info("Created initial shard for '{}'", shardName);
    }
    
    private int getShardId(K key) {
        int hash = Math.abs(hashFunction.apply(key));
        int currentShardCount = shardCount.get();
        return hash % currentShardCount;
    }
    
    private int defaultHashFunction(K key) {
        return key != null ? key.hashCode() : 0;
    }
    
    private ShardNode<K, V> getOrCreateShard(int shardId) {
        return shards.computeIfAbsent(shardId, id -> {
            ShardNode<K, V> newShard = new ShardNode<>(id, shardName + "-" + id);
            shardCount.set(Math.max(shardCount.get(), id + 1));
            LOGGER.info("Created new shard {} for '{}'", id, shardName);
            return newShard;
        });
    }
    
    private V findValueInAnyShaard(K key) {
        // Fallback search across all shards (less efficient but ensures data retrieval)
        for (ShardNode<K, V> shard : shards.values()) {
            V value = shard.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }
    
    private void startMonitoring() {
        // Monitor shard performance every 30 seconds
        scheduler.scheduleAtFixedRate(() -> {
            try {
                shardMonitor.checkShardHealth();
            } catch (Exception e) {
                LOGGER.error("Error monitoring shard health", e);
            }
        }, 30, 30, TimeUnit.SECONDS);
        
        // Auto-scaling check every 2 minutes
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (autoScalingEnabled) {
                    checkAutoScaling();
                }
            } catch (Exception e) {
                LOGGER.error("Error in auto-scaling check", e);
            }
        }, 120, 120, TimeUnit.SECONDS);
        
        // Generate performance report every 10 minutes
        scheduler.scheduleAtFixedRate(() -> {
            try {
                generatePerformanceReport();
            } catch (Exception e) {
                LOGGER.error("Error generating sharding performance report", e);
            }
        }, 600, 600, TimeUnit.SECONDS);
    }
    
    private void checkAutoScaling() {
        ShardingStats stats = getShardingStats();
        
        if (stats.maxLoad > scaleUpThreshold && stats.totalShards < maxShards) {
            LOGGER.info("High load detected ({:.1f}%) - scaling up shards for '{}'", 
                       stats.maxLoad * 100, shardName);
            scaleShards(ScaleDirection.UP, 1);
        } else if (stats.avgLoad < scaleDownThreshold && stats.totalShards > minShards) {
            LOGGER.info("Low load detected ({:.1f}%) - scaling down shards for '{}'", 
                       stats.avgLoad * 100, shardName);
            scaleShards(ScaleDirection.DOWN, 1);
        }
    }
    
    private void scheduleShardSplit(int shardId) {
        scheduler.submit(() -> {
            try {
                splitShard(shardId);
            } catch (Exception e) {
                LOGGER.error("Error splitting shard {}", shardId, e);
            }
        });
    }
    
    private void splitShard(int shardId) {
        ShardNode<K, V> originalShard = shards.get(shardId);
        if (originalShard == null) return;
        
        LOGGER.info("Splitting shard {} due to high load", shardId);
        
        // Create new shard
        int newShardId = shardCount.getAndIncrement();
        ShardNode<K, V> newShard = new ShardNode<>(newShardId, shardName + "-" + newShardId);
        shards.put(newShardId, newShard);
        
        // Redistribute data (simplified approach - would be more sophisticated in production)
        Map<K, V> originalData = new HashMap<>(originalShard.getAllData());
        originalShard.clear();
        
        for (Map.Entry<K, V> entry : originalData.entrySet()) {
            put(entry.getKey(), entry.getValue()); // Re-insert with new hash function
        }
        
        metrics.incrementCounter("sharding." + shardName + ".splits");
        LOGGER.info("Shard split completed - created shard {}", newShardId);
    }
    
    private ScaleResult scaleUp(int count) {
        int successCount = 0;
        
        for (int i = 0; i < count; i++) {
            try {
                int newShardId = shardCount.getAndIncrement();
                ShardNode<K, V> newShard = new ShardNode<>(newShardId, shardName + "-" + newShardId);
                shards.put(newShardId, newShard);
                successCount++;
                
                LOGGER.info("Scaled up: created shard {} for '{}'", newShardId, shardName);
            } catch (Exception e) {
                LOGGER.error("Failed to create shard during scale up", e);
                break;
            }
        }
        
        if (successCount > 0) {
            // Trigger rebalance after scaling
            rebalanceShards();
        }
        
        return new ScaleResult(successCount == count, 
                             String.format("Created %d new shards", successCount), shards.size());
    }
    
    private ScaleResult scaleDown(int count) {
        if (shards.size() - count < minShards) {
            return new ScaleResult(false, "Cannot scale below minimum shard count", shards.size());
        }
        
        // Find least loaded shards to remove
        List<ShardNode<K, V>> leastLoaded = shards.values().stream()
            .sorted(Comparator.comparingDouble(s -> s.getStats().loadFactor))
            .limit(count)
            .collect(Collectors.toList());
        
        int successCount = 0;
        
        for (ShardNode<K, V> shard : leastLoaded) {
            try {
                // Migrate data from this shard to others
                migrateShardData(shard);
                shards.remove(shard.getShardId());
                successCount++;
                
                LOGGER.info("Scaled down: removed shard {} from '{}'", shard.getShardId(), shardName);
            } catch (Exception e) {
                LOGGER.error("Failed to remove shard during scale down", e);
                break;
            }
        }
        
        return new ScaleResult(successCount == count,
                             String.format("Removed %d shards", successCount), shards.size());
    }
    
    private void migrateShardData(ShardNode<K, V> sourceShard) {
        Map<K, V> dataToMigrate = sourceShard.getAllData();
        
        for (Map.Entry<K, V> entry : dataToMigrate.entrySet()) {
            // Find new target shard and migrate data
            int newShardId = getShardId(entry.getKey());
            ShardNode<K, V> targetShard = shards.get(newShardId);
            
            if (targetShard != null && !targetShard.equals(sourceShard)) {
                targetShard.put(entry.getKey(), entry.getValue());
            }
        }
        
        sourceShard.clear();
    }
    
    private RebalanceResult performRebalance(long startTime) {
        LOGGER.info("Starting shard rebalance for '{}'", shardName);
        
        // Collect all data from all shards
        Map<K, V> allData = new HashMap<>();
        int itemsMoved = 0;
        
        for (ShardNode<K, V> shard : shards.values()) {
            Map<K, V> shardData = shard.getAllData();
            allData.putAll(shardData);
            itemsMoved += shardData.size();
        }
        
        // Clear all shards
        for (ShardNode<K, V> shard : shards.values()) {
            shard.clear();
        }
        
        // Redistribute data using current hash function
        for (Map.Entry<K, V> entry : allData.entrySet()) {
            put(entry.getKey(), entry.getValue());
        }
        
        long executionTime = System.currentTimeMillis() - startTime;
        lastRebalance.set(System.currentTimeMillis());
        rebalanceCount.incrementAndGet();
        
        String message = String.format("Rebalanced %d items across %d shards in %dms", 
                                     itemsMoved, shards.size(), executionTime);
        LOGGER.info(message);
        
        return new RebalanceResult(true, message, itemsMoved, executionTime);
    }
    
    private void generatePerformanceReport() {
        ShardingStats stats = getShardingStats();
        
        StringBuilder report = new StringBuilder();
        report.append(String.format("\n=== Sharding Performance Report: %s ===\n", shardName));
        report.append(String.format("Total Shards: %d\n", stats.totalShards));
        report.append(String.format("Total Items: %,d\n", stats.totalItems));
        report.append(String.format("Operations - Reads: %,d, Writes: %,d\n", stats.totalReads, stats.totalWrites));
        report.append(String.format("Load - Average: %.1f%%, Max: %.1f%%\n", 
                     stats.avgLoad * 100, stats.maxLoad * 100));
        report.append(String.format("Rebalances: %d (Last: %s ago)\n", 
                     stats.rebalanceCount, formatDuration(stats.timeSinceLastRebalance)));
        
        if (!stats.shardStats.isEmpty()) {
            report.append("\nTop 5 Shards by Load:\n");
            stats.shardStats.stream()
                 .sorted((a, b) -> Double.compare(b.loadFactor, a.loadFactor))
                 .limit(5)
                 .forEach(s -> report.append(String.format("  Shard %d: %.1f%% load, %,d items\n", 
                     s.shardId, s.loadFactor * 100, s.itemCount)));
        }
        
        report.append("==========================================\n");
        LOGGER.info(report.toString());
    }
    
    private String formatDuration(long ms) {
        if (ms < 60000) return ms/1000 + "s";
        if (ms < 3600000) return ms/60000 + "m";
        return ms/3600000 + "h";
    }
    
    /**
     * Shutdown the auto-sharding manager
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        LOGGER.info("Auto-sharding manager '{}' shut down", shardName);
    }
    
    // Enums and data classes
    
    public enum ScaleDirection {
        UP, DOWN
    }
    
    public static class RebalanceResult {
        public final boolean success;
        public final String message;
        public final int itemsMoved;
        public final long executionTimeMs;
        
        public RebalanceResult(boolean success, String message, int itemsMoved, long executionTimeMs) {
            this.success = success;
            this.message = message;
            this.itemsMoved = itemsMoved;
            this.executionTimeMs = executionTimeMs;
        }
    }
    
    public static class ScaleResult {
        public final boolean success;
        public final String message;
        public final int newShardCount;
        
        public ScaleResult(boolean success, String message, int newShardCount) {
            this.success = success;
            this.message = message;
            this.newShardCount = newShardCount;
        }
    }
    
    public static class ShardingStats {
        public final String shardName;
        public final int totalShards;
        public final long totalItems;
        public final long totalReads;
        public final long totalWrites;
        public final double avgLoad;
        public final double maxLoad;
        public final long rebalanceCount;
        public final long timeSinceLastRebalance;
        public final boolean autoScalingEnabled;
        public final List<ShardStats> shardStats;
        
        public ShardingStats(String shardName, int totalShards, long totalItems,
                           long totalReads, long totalWrites, double avgLoad, double maxLoad,
                           long rebalanceCount, long timeSinceLastRebalance, 
                           boolean autoScalingEnabled, List<ShardStats> shardStats) {
            this.shardName = shardName;
            this.totalShards = totalShards;
            this.totalItems = totalItems;
            this.totalReads = totalReads;
            this.totalWrites = totalWrites;
            this.avgLoad = avgLoad;
            this.maxLoad = maxLoad;
            this.rebalanceCount = rebalanceCount;
            this.timeSinceLastRebalance = timeSinceLastRebalance;
            this.autoScalingEnabled = autoScalingEnabled;
            this.shardStats = shardStats;
        }
    }
}