package com.eu.habbo.core.sharding;

import com.eu.habbo.util.collections.OptimizedConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Support components for the auto-sharding system
 */
public class ShardingComponents {
    
    /**
     * Individual shard node that stores data and tracks metrics
     */
    public static class ShardNode<K, V> {
        private static final Logger LOGGER = LoggerFactory.getLogger(ShardNode.class);
        
        private final int shardId;
        private final String shardName;
        private final OptimizedConcurrentMap<K, V> data;
        
        // Performance metrics
        private final LongAdder readCount = new LongAdder();
        private final LongAdder writeCount = new LongAdder();
        private final AtomicLong lastAccessTime = new AtomicLong(System.currentTimeMillis());
        
        // Load thresholds
        private static final int SPLIT_THRESHOLD = 10000; // Split when shard has >10k items
        private static final double LOAD_FACTOR_THRESHOLD = 0.85;
        
        public ShardNode(int shardId, String shardName) {
            this.shardId = shardId;
            this.shardName = shardName;
            this.data = new OptimizedConcurrentMap<>(shardName, 1024);
        }
        
        public V get(K key) {
            lastAccessTime.set(System.currentTimeMillis());
            return data.get(key);
        }
        
        public void put(K key, V value) {
            data.put(key, value);
            lastAccessTime.set(System.currentTimeMillis());
        }
        
        public V remove(K key) {
            lastAccessTime.set(System.currentTimeMillis());
            return data.remove(key);
        }
        
        public boolean containsKey(K key) {
            return data.containsKey(key);
        }
        
        public int size() {
            return data.size();
        }
        
        public boolean isEmpty() {
            return data.isEmpty();
        }
        
        public void clear() {
            data.clear();
        }
        
        public Collection<V> getAllValues() {
            return new ArrayList<>(data.values());
        }
        
        public Map<K, V> getAllData() {
            return new HashMap<>(data);
        }
        
        public void recordRead() {
            readCount.increment();
        }
        
        public void recordWrite() {
            writeCount.increment();
        }
        
        public boolean shouldSplit() {
            return data.size() > SPLIT_THRESHOLD || getLoadFactor() > LOAD_FACTOR_THRESHOLD;
        }
        
        public double getLoadFactor() {
            // Simple load factor based on size and operation frequency
            long totalOps = readCount.sum() + writeCount.sum();
            double sizeLoad = Math.min(1.0, data.size() / (double) SPLIT_THRESHOLD);
            double opsLoad = Math.min(1.0, totalOps / 10000.0); // Normalize to 10k ops
            return (sizeLoad * 0.7) + (opsLoad * 0.3);
        }
        
        public ShardStats getStats() {
            return new ShardStats(
                shardId, shardName, data.size(),
                readCount.sum(), writeCount.sum(),
                getLoadFactor(), lastAccessTime.get()
            );
        }
        
        public int getShardId() { return shardId; }
        public String getShardName() { return shardName; }
        
        @Override
        public String toString() {
            return String.format("ShardNode{id=%d, name='%s', size=%d, load=%.1f%%}", 
                               shardId, shardName, data.size(), getLoadFactor() * 100);
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            ShardNode<?, ?> that = (ShardNode<?, ?>) obj;
            return shardId == that.shardId;
        }
        
        @Override
        public int hashCode() {
            return Integer.hashCode(shardId);
        }
    }
    
    /**
     * Load balancer for shard operations
     */
    public static class ShardLoadBalancer<K, V> {
        private static final Logger LOGGER = LoggerFactory.getLogger(ShardLoadBalancer.class);
        
        private final AutoShardingManager<K, V> shardingManager;
        private final Map<Integer, Double> shardWeights = new HashMap<>();
        
        public ShardLoadBalancer(AutoShardingManager<K, V> shardingManager) {
            this.shardingManager = shardingManager;
        }
        
        /**
         * Get optimal shard for read operations
         */
        public ShardNode<K, V> getOptimalShardForRead(K key) {
            // For reads, we can potentially use any shard that has the data
            // In the future, could implement read replicas
            return getBestShardForKey(key);
        }
        
        /**
         * Get optimal shard for write operations
         */
        public ShardNode<K, V> getOptimalShardForWrite(K key) {
            // For writes, must use the correct shard based on hash
            return getBestShardForKey(key);
        }
        
        private ShardNode<K, V> getBestShardForKey(K key) {
            // In current implementation, this delegates to the sharding manager
            // Future enhancements could implement more sophisticated load balancing
            return null; // Placeholder - would integrate with AutoShardingManager
        }
        
        /**
         * Update shard weights based on performance
         */
        public void updateShardWeights(Map<Integer, ShardStats> shardStats) {
            for (Map.Entry<Integer, ShardStats> entry : shardStats.entrySet()) {
                int shardId = entry.getKey();
                ShardStats stats = entry.getValue();
                
                // Calculate weight based on inverse of load factor
                double weight = 1.0 - Math.min(0.9, stats.loadFactor);
                shardWeights.put(shardId, weight);
            }
        }
        
        /**
         * Get load balancing recommendations
         */
        public List<LoadBalanceRecommendation> getRecommendations(Map<Integer, ShardStats> shardStats) {
            List<LoadBalanceRecommendation> recommendations = new ArrayList<>();
            
            // Find overloaded and underloaded shards
            List<ShardStats> overloaded = new ArrayList<>();
            List<ShardStats> underloaded = new ArrayList<>();
            
            for (ShardStats stats : shardStats.values()) {
                if (stats.loadFactor > 0.8) {
                    overloaded.add(stats);
                } else if (stats.loadFactor < 0.3) {
                    underloaded.add(stats);
                }
            }
            
            // Generate recommendations
            if (!overloaded.isEmpty()) {
                for (ShardStats stats : overloaded) {
                    recommendations.add(new LoadBalanceRecommendation(
                        stats.shardId, RecommendationType.SCALE_UP,
                        String.format("Shard %d is overloaded (%.1f%%)", stats.shardId, stats.loadFactor * 100)
                    ));
                }
            }
            
            if (overloaded.isEmpty() && underloaded.size() > 1) {
                recommendations.add(new LoadBalanceRecommendation(
                    -1, RecommendationType.SCALE_DOWN,
                    String.format("%d shards are underutilized", underloaded.size())
                ));
            }
            
            return recommendations;
        }
    }
    
    /**
     * Monitor for shard health and performance
     */
    public static class ShardMonitor {
        private static final Logger LOGGER = LoggerFactory.getLogger(ShardMonitor.class);
        
        private final AutoShardingManager<?, ?> shardingManager;
        private final Map<Integer, ShardHealthStatus> healthHistory = new HashMap<>();
        
        public ShardMonitor(AutoShardingManager<?, ?> shardingManager) {
            this.shardingManager = shardingManager;
        }
        
        /**
         * Check health of all shards
         */
        public void checkShardHealth() {
            // Get current stats and check for issues
            // This would integrate with the sharding manager to get shard statistics
            LOGGER.debug("Checking shard health for manager: {}", 
                        shardingManager.getClass().getSimpleName());
            
            // Implementation would check:
            // - Shard responsiveness
            // - Memory usage per shard
            // - Operation latency
            // - Error rates
        }
        
        /**
         * Get health status for a specific shard
         */
        public ShardHealthStatus getShardHealth(int shardId) {
            return healthHistory.getOrDefault(shardId, ShardHealthStatus.UNKNOWN);
        }
        
        /**
         * Record health status for a shard
         */
        public void recordShardHealth(int shardId, ShardHealthStatus status) {
            healthHistory.put(shardId, status);
            
            if (status == ShardHealthStatus.UNHEALTHY) {
                LOGGER.warn("Shard {} marked as unhealthy", shardId);
            }
        }
    }
    
    // Data classes
    
    public static class ShardStats {
        public final int shardId;
        public final String shardName;
        public final int itemCount;
        public final long readCount;
        public final long writeCount;
        public final double loadFactor;
        public final long lastAccessTime;
        
        public ShardStats(int shardId, String shardName, int itemCount,
                         long readCount, long writeCount, double loadFactor, long lastAccessTime) {
            this.shardId = shardId;
            this.shardName = shardName;
            this.itemCount = itemCount;
            this.readCount = readCount;
            this.writeCount = writeCount;
            this.loadFactor = loadFactor;
            this.lastAccessTime = lastAccessTime;
        }
        
        @Override
        public String toString() {
            return String.format("ShardStats{id=%d, items=%d, load=%.1f%%, ops=%d}", 
                               shardId, itemCount, loadFactor * 100, readCount + writeCount);
        }
    }
    
    public static class LoadBalanceRecommendation {
        public final int shardId;
        public final RecommendationType type;
        public final String reason;
        
        public LoadBalanceRecommendation(int shardId, RecommendationType type, String reason) {
            this.shardId = shardId;
            this.type = type;
            this.reason = reason;
        }
        
        @Override
        public String toString() {
            return String.format("LoadBalanceRecommendation{shard=%d, type=%s, reason='%s'}", 
                               shardId, type, reason);
        }
    }
    
    public enum RecommendationType {
        SCALE_UP, SCALE_DOWN, REBALANCE, MIGRATE
    }
    
    public enum ShardHealthStatus {
        HEALTHY, DEGRADED, UNHEALTHY, UNKNOWN
    }
}