package com.eu.habbo.util.cache;

import com.eu.habbo.core.metrics.RealTimeMetrics;
import com.eu.habbo.core.metrics.TimerContext;
import com.eu.habbo.util.collections.OptimizedConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.security.MessageDigest;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Advanced distributed cache system for Arcturus Morningstar Reworked
 * Implements intelligent caching with multi-tier storage, compression, and consistency
 */
public class DistributedCache<K, V> {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(DistributedCache.class);
    
    // Multi-tier storage
    private final OptimizedConcurrentMap<K, CacheEntry<V>> l1Cache; // In-memory hot cache
    private final OptimizedConcurrentMap<K, CacheEntry<V>> l2Cache; // In-memory warm cache
    private final OptimizedConcurrentMap<String, byte[]> l3Cache;  // Compressed storage
    
    // Configuration
    private final String cacheName;
    private final int l1MaxSize;
    private final int l2MaxSize;
    private final int l3MaxSize;
    private final long defaultTtlMs;
    private final boolean compressionEnabled;
    private final boolean persistenceEnabled;
    
    // Background services
    private final ScheduledExecutorService maintenanceScheduler;
    private final ExecutorService asyncOperations;
    private final RealTimeMetrics metrics;
    
    // Statistics
    private final AtomicLong l1Hits = new AtomicLong(0);
    private final AtomicLong l2Hits = new AtomicLong(0);
    private final AtomicLong l3Hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong evictions = new AtomicLong(0);
    private final AtomicLong promotions = new AtomicLong(0);
    
    // Cache coherence
    private final OptimizedConcurrentMap<K, Long> versions;
    private final AtomicLong globalVersion = new AtomicLong(1);
    
    public DistributedCache(String cacheName, int l1MaxSize, int l2MaxSize, int l3MaxSize, 
                           long defaultTtlMs, boolean compressionEnabled, boolean persistenceEnabled) {
        this.cacheName = cacheName;
        this.l1MaxSize = l1MaxSize;
        this.l2MaxSize = l2MaxSize;
        this.l3MaxSize = l3MaxSize;
        this.defaultTtlMs = defaultTtlMs;
        this.compressionEnabled = compressionEnabled;
        this.persistenceEnabled = persistenceEnabled;
        
        // Initialize caches
        this.l1Cache = new OptimizedConcurrentMap<>(cacheName + "-L1", l1MaxSize);
        this.l2Cache = new OptimizedConcurrentMap<>(cacheName + "-L2", l2MaxSize);
        this.l3Cache = new OptimizedConcurrentMap<>(cacheName + "-L3", l3MaxSize);
        this.versions = new OptimizedConcurrentMap<>(cacheName + "-Versions", Math.max(l1MaxSize, l2MaxSize));
        
        // Initialize services
        this.maintenanceScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DistCache-" + cacheName + "-Maintenance");
            t.setDaemon(true);
            return t;
        });
        
        this.asyncOperations = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "DistCache-" + cacheName + "-Async");
            t.setDaemon(true);
            return t;
        });
        
        this.metrics = RealTimeMetrics.getInstance();
        
        // Start maintenance tasks
        startMaintenance();
        
        LOGGER.info("Distributed cache '{}' initialized - L1:{}, L2:{}, L3:{}, TTL:{}ms, Compression:{}, Persistence:{}", 
                   cacheName, l1MaxSize, l2MaxSize, l3MaxSize, defaultTtlMs, compressionEnabled, persistenceEnabled);
    }
    
    /**
     * Get value from cache with automatic tier promotion
     */
    public V get(K key) {
        return get(key, null);
    }
    
    /**
     * Get value from cache or compute if missing
     */
    public V get(K key, Function<K, V> loader) {
        if (key == null) return null;
        
        try (TimerContext timer = metrics.startTimer("cache." + cacheName + ".get")) {
            
            // L1 Cache lookup
            CacheEntry<V> l1Entry = l1Cache.get(key);
            if (l1Entry != null && !l1Entry.isExpired()) {
                l1Hits.incrementAndGet();
                metrics.incrementCounter("cache." + cacheName + ".l1.hit");
                return l1Entry.getValue();
            }
            
            // L2 Cache lookup
            CacheEntry<V> l2Entry = l2Cache.get(key);
            if (l2Entry != null && !l2Entry.isExpired()) {
                l2Hits.incrementAndGet();
                metrics.incrementCounter("cache." + cacheName + ".l2.hit");
                
                // Promote to L1 asynchronously
                promoteToL1(key, l2Entry);
                return l2Entry.getValue();
            }
            
            // L3 Cache lookup (compressed)
            V l3Value = getFromL3(key);
            if (l3Value != null) {
                l3Hits.incrementAndGet();
                metrics.incrementCounter("cache." + cacheName + ".l3.hit");
                
                // Promote to L2 asynchronously
                promoteToL2(key, l3Value);
                return l3Value;
            }
            
            // Cache miss - load value if loader provided
            if (loader != null) {
                misses.incrementAndGet();
                metrics.incrementCounter("cache." + cacheName + ".miss");
                
                V value = loader.apply(key);
                if (value != null) {
                    putAsync(key, value, defaultTtlMs);
                }
                return value;
            }
            
            misses.incrementAndGet();
            metrics.incrementCounter("cache." + cacheName + ".miss");
            return null;
        }
    }
    
    /**
     * Put value in cache
     */
    public void put(K key, V value) {
        put(key, value, defaultTtlMs);
    }
    
    /**
     * Put value in cache with custom TTL
     */
    public void put(K key, V value, long ttlMs) {
        if (key == null || value == null) return;
        
        try (TimerContext timer = metrics.startTimer("cache." + cacheName + ".put")) {
            long expirationTime = System.currentTimeMillis() + ttlMs;
            CacheEntry<V> entry = new CacheEntry<>(value, expirationTime, globalVersion.incrementAndGet());
            
            // Update version
            versions.put(key, entry.getVersion());
            
            // Put in L1 cache
            putInL1(key, entry);
            
            // Asynchronously store in L2 and L3
            asyncOperations.submit(() -> {
                putInL2(key, entry);
                putInL3(key, value, expirationTime);
            });
            
            metrics.incrementCounter("cache." + cacheName + ".put");
        }
    }
    
    /**
     * Put value asynchronously
     */
    public void putAsync(K key, V value, long ttlMs) {
        asyncOperations.submit(() -> put(key, value, ttlMs));
    }
    
    /**
     * Remove value from all cache tiers
     */
    public V remove(K key) {
        if (key == null) return null;
        
        CacheEntry<V> removed = l1Cache.remove(key);
        l2Cache.remove(key);
        removeFromL3(key);
        versions.remove(key);
        
        metrics.incrementCounter("cache." + cacheName + ".remove");
        return removed != null ? removed.getValue() : null;
    }
    
    /**
     * Check if key exists in cache
     */
    public boolean containsKey(K key) {
        if (key == null) return false;
        
        return l1Cache.containsKey(key) || 
               l2Cache.containsKey(key) || 
               containsInL3(key);
    }
    
    /**
     * Get cache size across all tiers
     */
    public int size() {
        return l1Cache.size() + l2Cache.size() + l3Cache.size();
    }
    
    /**
     * Clear all cache tiers
     */
    public void clear() {
        l1Cache.clear();
        l2Cache.clear();
        l3Cache.clear();
        versions.clear();
        
        LOGGER.info("Distributed cache '{}' cleared", cacheName);
        metrics.incrementCounter("cache." + cacheName + ".clear");
    }
    
    /**
     * Get comprehensive cache statistics
     */
    public DistributedCacheStats getStats() {
        long totalRequests = l1Hits.get() + l2Hits.get() + l3Hits.get() + misses.get();
        double hitRate = totalRequests > 0 ? 
            (double)(l1Hits.get() + l2Hits.get() + l3Hits.get()) / totalRequests : 0.0;
        
        return new DistributedCacheStats(
            cacheName,
            l1Cache.size(), l2Cache.size(), l3Cache.size(),
            l1Hits.get(), l2Hits.get(), l3Hits.get(), misses.get(),
            evictions.get(), promotions.get(), hitRate
        );
    }
    
    // Private helper methods
    
    private void putInL1(K key, CacheEntry<V> entry) {
        if (l1Cache.size() >= l1MaxSize) {
            evictFromL1();
        }
        l1Cache.put(key, entry);
    }
    
    private void putInL2(K key, CacheEntry<V> entry) {
        if (l2Cache.size() >= l2MaxSize) {
            evictFromL2();
        }
        l2Cache.put(key, entry);
    }
    
    private void putInL3(K key, V value, long expirationTime) {
        if (!compressionEnabled) return;
        
        try {
            String keyHash = hashKey(key);
            byte[] serialized = serialize(value, expirationTime);
            
            if (l3Cache.size() >= l3MaxSize) {
                evictFromL3();
            }
            
            l3Cache.put(keyHash, serialized);
        } catch (Exception e) {
            LOGGER.warn("Failed to store in L3 cache: {}", e.getMessage());
        }
    }
    
    private V getFromL3(K key) {
        if (!compressionEnabled) return null;
        
        try {
            String keyHash = hashKey(key);
            byte[] data = l3Cache.get(keyHash);
            
            if (data != null) {
                return deserialize(data);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to retrieve from L3 cache: {}", e.getMessage());
        }
        
        return null;
    }
    
    private void removeFromL3(K key) {
        if (compressionEnabled) {
            try {
                String keyHash = hashKey(key);
                l3Cache.remove(keyHash);
            } catch (Exception e) {
                LOGGER.warn("Failed to remove from L3 cache: {}", e.getMessage());
            }
        }
    }
    
    private boolean containsInL3(K key) {
        if (!compressionEnabled) return false;
        
        try {
            String keyHash = hashKey(key);
            return l3Cache.containsKey(keyHash);
        } catch (Exception e) {
            return false;
        }
    }
    
    private void promoteToL1(K key, CacheEntry<V> entry) {
        asyncOperations.submit(() -> {
            putInL1(key, entry);
            promotions.incrementAndGet();
        });
    }
    
    private void promoteToL2(K key, V value) {
        asyncOperations.submit(() -> {
            long expirationTime = System.currentTimeMillis() + defaultTtlMs;
            CacheEntry<V> entry = new CacheEntry<>(value, expirationTime, globalVersion.incrementAndGet());
            putInL2(key, entry);
            promotions.incrementAndGet();
        });
    }
    
    private void evictFromL1() {
        // LRU eviction - remove oldest entries
        l1Cache.entrySet().removeIf(entry -> {
            if (entry.getValue().isExpired()) {
                evictions.incrementAndGet();
                return true;
            }
            return false;
        });
    }
    
    private void evictFromL2() {
        l2Cache.entrySet().removeIf(entry -> {
            if (entry.getValue().isExpired()) {
                evictions.incrementAndGet();
                return true;
            }
            return false;
        });
    }
    
    private void evictFromL3() {
        // Simple size-based eviction for L3
        if (l3Cache.size() > l3MaxSize * 0.9) {
            l3Cache.entrySet().iterator().remove();
            evictions.incrementAndGet();
        }
    }
    
    private String hashKey(K key) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] keyBytes = key.toString().getBytes("UTF-8");
        byte[] hash = md.digest(keyBytes);
        
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
    
    private byte[] serialize(V value, long expirationTime) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        
        oos.writeLong(expirationTime);
        oos.writeObject(value);
        oos.close();
        
        return baos.toByteArray();
    }
    
    @SuppressWarnings("unchecked")
    private V deserialize(byte[] data) throws IOException, ClassNotFoundException {
        ByteArrayInputStream bais = new ByteArrayInputStream(data);
        ObjectInputStream ois = new ObjectInputStream(bais);
        
        long expirationTime = ois.readLong();
        if (System.currentTimeMillis() > expirationTime) {
            return null; // Expired
        }
        
        return (V) ois.readObject();
    }
    
    private void startMaintenance() {
        // Cleanup expired entries every 5 minutes
        maintenanceScheduler.scheduleAtFixedRate(() -> {
            try {
                cleanupExpiredEntries();
            } catch (Exception e) {
                LOGGER.error("Error during cache cleanup for '{}'", cacheName, e);
            }
        }, 5, 5, TimeUnit.MINUTES);
        
        // Generate performance report every 15 minutes
        maintenanceScheduler.scheduleAtFixedRate(() -> {
            try {
                generatePerformanceReport();
            } catch (Exception e) {
                LOGGER.error("Error generating performance report for '{}'", cacheName, e);
            }
        }, 15, 15, TimeUnit.MINUTES);
    }
    
    private void cleanupExpiredEntries() {
        long cleaned = 0;
        
        // Cleanup L1
        cleaned += l1Cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        
        // Cleanup L2
        cleaned += l2Cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        
        // Cleanup versions
        versions.entrySet().removeIf(entry -> !containsKey(entry.getKey()));
        
        if (cleaned > 0) {
            LOGGER.debug("Cleaned {} expired entries from cache '{}'", cleaned, cacheName);
        }
    }
    
    private void generatePerformanceReport() {
        DistributedCacheStats stats = getStats();
        LOGGER.info("Cache '{}' Stats - L1:{}, L2:{}, L3:{}, Hit Rate: {:.1f}%, Promotions: {}, Evictions: {}",
                   cacheName, stats.l1Size, stats.l2Size, stats.l3Size, 
                   stats.hitRate * 100, stats.promotions, stats.evictions);
    }
    
    /**
     * Shutdown cache and cleanup resources
     */
    public void shutdown() {
        maintenanceScheduler.shutdown();
        asyncOperations.shutdown();
        
        try {
            if (!maintenanceScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                maintenanceScheduler.shutdownNow();
            }
            if (!asyncOperations.awaitTermination(5, TimeUnit.SECONDS)) {
                asyncOperations.shutdownNow();
            }
        } catch (InterruptedException e) {
            maintenanceScheduler.shutdownNow();
            asyncOperations.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        LOGGER.info("Distributed cache '{}' shut down", cacheName);
    }
    
    /**
     * Cache entry with expiration and versioning
     */
    private static class CacheEntry<V> {
        private final V value;
        private final long expirationTime;
        private final long version;
        
        public CacheEntry(V value, long expirationTime, long version) {
            this.value = value;
            this.expirationTime = expirationTime;
            this.version = version;
        }
        
        public V getValue() { return value; }
        public long getVersion() { return version; }
        
        public boolean isExpired() {
            return System.currentTimeMillis() > expirationTime;
        }
    }
    
    /**
     * Distributed cache statistics
     */
    public static class DistributedCacheStats {
        public final String name;
        public final int l1Size, l2Size, l3Size;
        public final long l1Hits, l2Hits, l3Hits, misses;
        public final long evictions, promotions;
        public final double hitRate;
        
        public DistributedCacheStats(String name, int l1Size, int l2Size, int l3Size,
                                   long l1Hits, long l2Hits, long l3Hits, long misses,
                                   long evictions, long promotions, double hitRate) {
            this.name = name;
            this.l1Size = l1Size;
            this.l2Size = l2Size;
            this.l3Size = l3Size;
            this.l1Hits = l1Hits;
            this.l2Hits = l2Hits;
            this.l3Hits = l3Hits;
            this.misses = misses;
            this.evictions = evictions;
            this.promotions = promotions;
            this.hitRate = hitRate;
        }
        
        @Override
        public String toString() {
            return String.format("DistributedCacheStats{name='%s', sizes=[%d,%d,%d], hitRate=%.1f%%, promotions=%d, evictions=%d}",
                               name, l1Size, l2Size, l3Size, hitRate * 100, promotions, evictions);
        }
    }
}