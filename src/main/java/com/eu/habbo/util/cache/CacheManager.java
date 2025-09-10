package com.eu.habbo.util.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * High-performance cache manager for Arcturus Morningstar Reworked
 * Implements TTL-based caching with automatic cleanup to improve performance
 * for frequently accessed data like user profiles, room data, and item information.
 */
public class CacheManager<K, V> {
    
    private static final ScheduledExecutorService CLEANUP_EXECUTOR = 
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Cache-Cleanup");
            t.setDaemon(true);
            return t;
        });
    
    private final ConcurrentHashMap<K, CacheEntry<V>> cache;
    private final long defaultTtlMs;
    
    public CacheManager(long defaultTtlMs) {
        this.cache = new ConcurrentHashMap<>(256, 0.75f, Runtime.getRuntime().availableProcessors());
        this.defaultTtlMs = defaultTtlMs;
        
        // Schedule cleanup every 5 minutes
        CLEANUP_EXECUTOR.scheduleAtFixedRate(this::cleanup, 5, 5, TimeUnit.MINUTES);
    }
    
    /**
     * Get value from cache or compute if missing/expired
     */
    public V get(K key, Supplier<V> valueSupplier) {
        return get(key, valueSupplier, defaultTtlMs);
    }
    
    /**
     * Get value from cache or compute if missing/expired with custom TTL
     */
    public V get(K key, Supplier<V> valueSupplier, long ttlMs) {
        CacheEntry<V> entry = cache.get(key);
        
        if (entry != null && !entry.isExpired()) {
            return entry.getValue();
        }
        
        // Compute new value
        V value = valueSupplier.get();
        if (value != null) {
            cache.put(key, new CacheEntry<>(value, System.currentTimeMillis() + ttlMs));
        }
        
        return value;
    }
    
    /**
     * Put value in cache with default TTL
     */
    public void put(K key, V value) {
        put(key, value, defaultTtlMs);
    }
    
    /**
     * Put value in cache with custom TTL
     */
    public void put(K key, V value, long ttlMs) {
        if (value != null) {
            cache.put(key, new CacheEntry<>(value, System.currentTimeMillis() + ttlMs));
        }
    }
    
    /**
     * Remove value from cache
     */
    public void remove(K key) {
        cache.remove(key);
    }
    
    /**
     * Clear all cached values
     */
    public void clear() {
        cache.clear();
    }
    
    /**
     * Get current cache size
     */
    public int size() {
        return cache.size();
    }
    
    /**
     * Remove expired entries from cache
     */
    private void cleanup() {
        long currentTime = System.currentTimeMillis();
        cache.entrySet().removeIf(entry -> entry.getValue().isExpired(currentTime));
    }
    
    /**
     * Cache entry with TTL
     */
    private static class CacheEntry<V> {
        private final V value;
        private final long expirationTime;
        
        CacheEntry(V value, long expirationTime) {
            this.value = value;
            this.expirationTime = expirationTime;
        }
        
        V getValue() {
            return value;
        }
        
        boolean isExpired() {
            return isExpired(System.currentTimeMillis());
        }
        
        boolean isExpired(long currentTime) {
            return currentTime >= expirationTime;
        }
    }
}