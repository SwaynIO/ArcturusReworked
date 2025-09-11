package com.eu.habbo.util.collections;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Optimized concurrent map with built-in performance monitoring
 * Provides better insights into map usage patterns for performance tuning
 */
public class OptimizedConcurrentMap<K, V> extends ConcurrentHashMap<K, V> {
    
    private static final long serialVersionUID = 1L;
    
    // Performance counters
    private final AtomicLong hitCount = new AtomicLong(0);
    private final AtomicLong missCount = new AtomicLong(0);
    private final AtomicLong putCount = new AtomicLong(0);
    private final AtomicLong removeCount = new AtomicLong(0);
    
    private final String mapName;
    private volatile long lastReportTime = System.currentTimeMillis();
    
    public OptimizedConcurrentMap(String mapName) {
        super();
        this.mapName = mapName;
    }
    
    public OptimizedConcurrentMap(String mapName, int initialCapacity) {
        super(initialCapacity);
        this.mapName = mapName;
    }
    
    public OptimizedConcurrentMap(String mapName, int initialCapacity, float loadFactor, int concurrencyLevel) {
        super(initialCapacity, loadFactor, concurrencyLevel);
        this.mapName = mapName;
    }
    
    @Override
    public V get(Object key) {
        V value = super.get(key);
        if (value != null) {
            hitCount.incrementAndGet();
        } else {
            missCount.incrementAndGet();
        }
        return value;
    }
    
    @Override
    public V put(K key, V value) {
        putCount.incrementAndGet();
        return super.put(key, value);
    }
    
    @Override
    public V remove(Object key) {
        V removed = super.remove(key);
        if (removed != null) {
            removeCount.incrementAndGet();
        }
        return removed;
    }
    
    @Override
    public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
        V existing = super.get(key);
        if (existing != null) {
            hitCount.incrementAndGet();
            return existing;
        } else {
            missCount.incrementAndGet();
            V computed = super.computeIfAbsent(key, mappingFunction);
            if (computed != null) {
                putCount.incrementAndGet();
            }
            return computed;
        }
    }
    
    /**
     * Get hit rate as percentage
     */
    public double getHitRate() {
        long hits = hitCount.get();
        long total = hits + missCount.get();
        return total > 0 ? (hits * 100.0) / total : 0.0;
    }
    
    /**
     * Get detailed statistics
     */
    public MapStats getStats() {
        return new MapStats(
            mapName,
            size(),
            hitCount.get(),
            missCount.get(),
            putCount.get(),
            removeCount.get(),
            getHitRate()
        );
    }
    
    /**
     * Reset performance counters
     */
    public void resetStats() {
        hitCount.set(0);
        missCount.set(0);
        putCount.set(0);
        removeCount.set(0);
        lastReportTime = System.currentTimeMillis();
    }
    
    /**
     * Get performance report if enough time has passed
     */
    public String getPerformanceReport(long reportIntervalMs) {
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastReportTime >= reportIntervalMs) {
            lastReportTime = currentTime;
            MapStats stats = getStats();
            return String.format(
                "[%s] Size: %d, Hits: %d, Misses: %d, Hit Rate: %.1f%%, Puts: %d, Removes: %d",
                stats.name, stats.size, stats.hits, stats.misses, 
                stats.hitRate, stats.puts, stats.removes
            );
        }
        return null;
    }
    
    /**
     * Statistics data class
     */
    public static class MapStats {
        public final String name;
        public final int size;
        public final long hits;
        public final long misses;
        public final long puts;
        public final long removes;
        public final double hitRate;
        
        public MapStats(String name, int size, long hits, long misses, 
                       long puts, long removes, double hitRate) {
            this.name = name;
            this.size = size;
            this.hits = hits;
            this.misses = misses;
            this.puts = puts;
            this.removes = removes;
            this.hitRate = hitRate;
        }
        
        @Override
        public String toString() {
            return String.format(
                "MapStats{name='%s', size=%d, hits=%d, misses=%d, hitRate=%.1f%%, puts=%d, removes=%d}",
                name, size, hits, misses, hitRate, puts, removes
            );
        }
    }
}