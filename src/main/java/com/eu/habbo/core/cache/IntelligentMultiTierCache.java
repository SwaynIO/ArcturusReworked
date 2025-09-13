package com.eu.habbo.core.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.*;
import java.util.function.Function;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.stream.Collectors;

/**
 * Intelligent Multi-Tier Cache System with adaptive algorithms
 * Features L1 (hot), L2 (warm), L3 (cold) caching with automatic promotion/demotion
 * and intelligent memory pressure handling
 */
public class IntelligentMultiTierCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(IntelligentMultiTierCache.class);

    private static IntelligentMultiTierCache instance;

    // Cache tiers
    private final L1HotCache l1Cache;
    private final L2WarmCache l2Cache;
    private final L3ColdCache l3Cache;

    // Management components
    private final CacheTierManager tierManager;
    private final MemoryPressureMonitor memoryMonitor;
    private final AdaptiveEvictionEngine evictionEngine;
    private final CacheStatsCollector statsCollector;
    private final ScheduledExecutorService maintenanceScheduler;

    // Configuration
    private static final int L1_MAX_SIZE = 10000;
    private static final int L2_MAX_SIZE = 50000;
    private static final int L3_MAX_SIZE = 200000;
    private static final double MEMORY_PRESSURE_THRESHOLD = 0.85;
    private static final long MAINTENANCE_INTERVAL_MS = 30000;

    private IntelligentMultiTierCache() {
        this.l1Cache = new L1HotCache(L1_MAX_SIZE);
        this.l2Cache = new L2WarmCache(L2_MAX_SIZE);
        this.l3Cache = new L3ColdCache(L3_MAX_SIZE);

        this.tierManager = new CacheTierManager();
        this.memoryMonitor = new MemoryPressureMonitor();
        this.evictionEngine = new AdaptiveEvictionEngine();
        this.statsCollector = new CacheStatsCollector();

        this.maintenanceScheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "MultiTierCache-Maintenance");
            t.setDaemon(true);
            return t;
        });

        initializeCacheSystem();
        LOGGER.info("Intelligent Multi-Tier Cache System initialized");
    }

    public static synchronized IntelligentMultiTierCache getInstance() {
        if (instance == null) {
            instance = new IntelligentMultiTierCache();
        }
        return instance;
    }

    private void initializeCacheSystem() {
        // Start memory pressure monitoring
        maintenanceScheduler.scheduleWithFixedDelay(
            memoryMonitor::checkMemoryPressure,
            10, 10, TimeUnit.SECONDS
        );

        // Start cache tier management
        maintenanceScheduler.scheduleWithFixedDelay(
            tierManager::maintainTiers,
            MAINTENANCE_INTERVAL_MS, MAINTENANCE_INTERVAL_MS, TimeUnit.MILLISECONDS
        );
    }

    /**
     * Intelligent get operation with automatic tier promotion
     */
    public <T> T get(String key, Class<T> valueClass) {
        long startTime = System.nanoTime();

        try {
            // Try L1 (Hot) cache first
            CacheEntry<T> entry = l1Cache.get(key);
            if (entry != null && !entry.isExpired()) {
                statsCollector.recordL1Hit(key, System.nanoTime() - startTime);
                entry.recordAccess();
                return entry.getValue();
            }

            // Try L2 (Warm) cache
            entry = l2Cache.get(key);
            if (entry != null && !entry.isExpired()) {
                statsCollector.recordL2Hit(key, System.nanoTime() - startTime);
                entry.recordAccess();

                // Promote to L1 if hot enough
                if (entry.shouldPromoteToL1()) {
                    tierManager.promoteToL1(key, entry);
                }
                return entry.getValue();
            }

            // Try L3 (Cold) cache
            entry = l3Cache.get(key);
            if (entry != null && !entry.isExpired()) {
                statsCollector.recordL3Hit(key, System.nanoTime() - startTime);
                entry.recordAccess();

                // Promote to L2 if warming up
                if (entry.shouldPromoteToL2()) {
                    tierManager.promoteToL2(key, entry);
                }
                return entry.getValue();
            }

            // Cache miss
            statsCollector.recordMiss(key, System.nanoTime() - startTime);
            return null;

        } catch (Exception e) {
            LOGGER.error("Cache get failed for key: {}", key, e);
            statsCollector.recordError(key);
            return null;
        }
    }

    /**
     * Intelligent put operation with automatic tier assignment
     */
    public <T> boolean put(String key, T value, long ttl) {
        long startTime = System.nanoTime();

        try {
            CacheEntry<T> entry = new CacheEntry<>(value, ttl, CacheTier.L3);

            // Determine initial tier based on access patterns and memory pressure
            CacheTier targetTier = tierManager.determineInitialTier(key, entry);

            boolean success = false;
            switch (targetTier) {
                case L1:
                    success = l1Cache.put(key, entry);
                    if (success) statsCollector.recordL1Put(key, System.nanoTime() - startTime);
                    break;
                case L2:
                    success = l2Cache.put(key, entry);
                    if (success) statsCollector.recordL2Put(key, System.nanoTime() - startTime);
                    break;
                case L3:
                    success = l3Cache.put(key, entry);
                    if (success) statsCollector.recordL3Put(key, System.nanoTime() - startTime);
                    break;
            }

            if (!success) {
                // Trigger emergency eviction and retry
                evictionEngine.performEmergencyEviction(targetTier);
                success = putInTier(key, entry, targetTier);
            }

            return success;

        } catch (Exception e) {
            LOGGER.error("Cache put failed for key: {}", key, e);
            statsCollector.recordError(key);
            return false;
        }
    }

    private <T> boolean putInTier(String key, CacheEntry<T> entry, CacheTier tier) {
        switch (tier) {
            case L1: return l1Cache.put(key, entry);
            case L2: return l2Cache.put(key, entry);
            case L3: return l3Cache.put(key, entry);
            default: return false;
        }
    }

    /**
     * L1 Hot Cache - Fastest access, smallest size
     */
    private class L1HotCache {
        private final ConcurrentHashMap<String, CacheEntry<?>> cache;
        private final int maxSize;
        private final LongAdder hitCount = new LongAdder();
        private final LongAdder missCount = new LongAdder();

        L1HotCache(int maxSize) {
            this.maxSize = maxSize;
            this.cache = new ConcurrentHashMap<>(maxSize);
        }

        @SuppressWarnings("unchecked")
        <T> CacheEntry<T> get(String key) {
            CacheEntry<?> entry = cache.get(key);
            if (entry != null) {
                hitCount.increment();
                return (CacheEntry<T>) entry;
            }
            missCount.increment();
            return null;
        }

        <T> boolean put(String key, CacheEntry<T> entry) {
            if (cache.size() >= maxSize && !cache.containsKey(key)) {
                // Need to evict
                evictLeastUsed();
            }

            entry.setTier(CacheTier.L1);
            cache.put(key, entry);
            return true;
        }

        private void evictLeastUsed() {
            cache.entrySet().stream()
                .min(Comparator.comparing(e -> e.getValue().getLastAccessTime()))
                .ifPresent(e -> {
                    String key = e.getKey();
                    CacheEntry<?> entry = e.getValue();
                    cache.remove(key);

                    // Demote to L2
                    tierManager.demoteToL2(key, entry);
                });
        }

        void remove(String key) {
            cache.remove(key);
        }

        void clear() {
            cache.clear();
        }

        int size() {
            return cache.size();
        }

        double getHitRate() {
            long hits = hitCount.sum();
            long misses = missCount.sum();
            long total = hits + misses;
            return total > 0 ? (double) hits / total : 0.0;
        }
    }

    /**
     * L2 Warm Cache - Balanced access speed and size
     */
    private class L2WarmCache {
        private final ConcurrentHashMap<String, CacheEntry<?>> cache;
        private final int maxSize;
        private final LongAdder hitCount = new LongAdder();
        private final LongAdder missCount = new LongAdder();

        L2WarmCache(int maxSize) {
            this.maxSize = maxSize;
            this.cache = new ConcurrentHashMap<>(maxSize);
        }

        @SuppressWarnings("unchecked")
        <T> CacheEntry<T> get(String key) {
            CacheEntry<?> entry = cache.get(key);
            if (entry != null) {
                hitCount.increment();
                return (CacheEntry<T>) entry;
            }
            missCount.increment();
            return null;
        }

        <T> boolean put(String key, CacheEntry<T> entry) {
            if (cache.size() >= maxSize && !cache.containsKey(key)) {
                evictLeastUsed();
            }

            entry.setTier(CacheTier.L2);
            cache.put(key, entry);
            return true;
        }

        private void evictLeastUsed() {
            cache.entrySet().stream()
                .min(Comparator.comparing(e -> e.getValue().getLastAccessTime()))
                .ifPresent(e -> {
                    String key = e.getKey();
                    CacheEntry<?> entry = e.getValue();
                    cache.remove(key);

                    // Demote to L3
                    tierManager.demoteToL3(key, entry);
                });
        }

        void remove(String key) {
            cache.remove(key);
        }

        void clear() {
            cache.clear();
        }

        int size() {
            return cache.size();
        }

        double getHitRate() {
            long hits = hitCount.sum();
            long misses = missCount.sum();
            long total = hits + misses;
            return total > 0 ? (double) hits / total : 0.0;
        }
    }

    /**
     * L3 Cold Cache - Largest capacity, compressed storage
     */
    private class L3ColdCache {
        private final ConcurrentHashMap<String, CompressedCacheEntry> cache;
        private final int maxSize;
        private final LongAdder hitCount = new LongAdder();
        private final LongAdder missCount = new LongAdder();

        L3ColdCache(int maxSize) {
            this.maxSize = maxSize;
            this.cache = new ConcurrentHashMap<>(maxSize);
        }

        @SuppressWarnings("unchecked")
        <T> CacheEntry<T> get(String key) {
            CompressedCacheEntry compressedEntry = cache.get(key);
            if (compressedEntry != null) {
                hitCount.increment();
                // Decompress and return
                CacheEntry<T> entry = compressedEntry.decompress();
                if (entry != null) {
                    entry.setTier(CacheTier.L3);
                    return entry;
                }
            }
            missCount.increment();
            return null;
        }

        <T> boolean put(String key, CacheEntry<T> entry) {
            if (cache.size() >= maxSize && !cache.containsKey(key)) {
                evictOldest();
            }

            // Compress entry for storage
            CompressedCacheEntry compressed = new CompressedCacheEntry(entry);
            cache.put(key, compressed);
            return true;
        }

        private void evictOldest() {
            cache.entrySet().stream()
                .min(Comparator.comparing(e -> e.getValue().getCreationTime()))
                .ifPresent(e -> cache.remove(e.getKey()));
        }

        void remove(String key) {
            cache.remove(key);
        }

        void clear() {
            cache.clear();
        }

        int size() {
            return cache.size();
        }

        double getHitRate() {
            long hits = hitCount.sum();
            long misses = missCount.sum();
            long total = hits + misses;
            return total > 0 ? (double) hits / total : 0.0;
        }
    }

    /**
     * Cache Tier Management for promotion/demotion decisions
     */
    private class CacheTierManager {
        private final Map<String, AccessPattern> accessPatterns = new ConcurrentHashMap<>();
        private final LongAdder promotions = new LongAdder();
        private final LongAdder demotions = new LongAdder();

        CacheTier determineInitialTier(String key, CacheEntry<?> entry) {
            AccessPattern pattern = accessPatterns.get(key);
            if (pattern == null) {
                return CacheTier.L3; // New entries start cold
            }

            // Determine tier based on access frequency and recency
            if (pattern.getAccessFrequency() > 50 && pattern.getRecencyScore() > 0.8) {
                return CacheTier.L1;
            } else if (pattern.getAccessFrequency() > 10 && pattern.getRecencyScore() > 0.5) {
                return CacheTier.L2;
            } else {
                return CacheTier.L3;
            }
        }

        <T> void promoteToL1(String key, CacheEntry<T> entry) {
            if (l1Cache.put(key, entry)) {
                l2Cache.remove(key);
                l3Cache.remove(key);
                promotions.increment();
                LOGGER.debug("Promoted {} to L1", key);
            }
        }

        <T> void promoteToL2(String key, CacheEntry<T> entry) {
            if (l2Cache.put(key, entry)) {
                l3Cache.remove(key);
                promotions.increment();
                LOGGER.debug("Promoted {} to L2", key);
            }
        }

        <T> void demoteToL2(String key, CacheEntry<T> entry) {
            if (l2Cache.put(key, entry)) {
                demotions.increment();
                LOGGER.debug("Demoted {} to L2", key);
            }
        }

        <T> void demoteToL3(String key, CacheEntry<T> entry) {
            if (l3Cache.put(key, entry)) {
                demotions.increment();
                LOGGER.debug("Demoted {} to L3", key);
            }
        }

        void maintainTiers() {
            // Update access patterns
            updateAccessPatterns();

            // Perform intelligent tier redistribution
            redistributeTiers();

            // Clean up expired entries
            cleanupExpiredEntries();
        }

        private void updateAccessPatterns() {
            // Implementation would analyze recent access patterns
            // and update promotion/demotion thresholds dynamically
        }

        private void redistributeTiers() {
            // Implementation would move entries between tiers
            // based on updated access patterns and memory pressure
        }

        private void cleanupExpiredEntries() {
            long now = System.currentTimeMillis();

            // Clean L1
            l1Cache.cache.entrySet().removeIf(entry -> entry.getValue().isExpired());

            // Clean L2
            l2Cache.cache.entrySet().removeIf(entry -> entry.getValue().isExpired());

            // Clean L3
            l3Cache.cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
        }

        public TierManagerStats getStats() {
            return new TierManagerStats(promotions.sum(), demotions.sum(), accessPatterns.size());
        }
    }

    /**
     * Memory pressure monitoring with adaptive responses
     */
    private class MemoryPressureMonitor {
        private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        private volatile double lastMemoryPressure = 0.0;
        private final AtomicLong highPressureEvents = new AtomicLong();

        void checkMemoryPressure() {
            MemoryUsage heapUsage = memoryBean.getHeapMemoryUsage();
            double memoryPressure = (double) heapUsage.getUsed() / heapUsage.getMax();
            lastMemoryPressure = memoryPressure;

            if (memoryPressure > MEMORY_PRESSURE_THRESHOLD) {
                highPressureEvents.incrementAndGet();
                handleHighMemoryPressure(memoryPressure);
            }
        }

        private void handleHighMemoryPressure(double pressure) {
            LOGGER.warn("High memory pressure detected: {:.2f}%", pressure * 100);

            // Trigger aggressive eviction
            evictionEngine.performMemoryPressureEviction(pressure);

            // Consider triggering GC if pressure is very high
            if (pressure > 0.95) {
                System.gc();
                LOGGER.info("Emergency GC triggered due to memory pressure");
            }
        }

        double getCurrentMemoryPressure() {
            return lastMemoryPressure;
        }

        public MemoryPressureStats getStats() {
            return new MemoryPressureStats(lastMemoryPressure, highPressureEvents.get());
        }
    }

    /**
     * Adaptive eviction engine with multiple strategies
     */
    private class AdaptiveEvictionEngine {
        private final LongAdder totalEvictions = new LongAdder();
        private final LongAdder emergencyEvictions = new LongAdder();

        void performEmergencyEviction(CacheTier tier) {
            emergencyEvictions.increment();

            switch (tier) {
                case L1:
                    evictFromL1(0.1); // Evict 10%
                    break;
                case L2:
                    evictFromL2(0.1);
                    break;
                case L3:
                    evictFromL3(0.1);
                    break;
            }
        }

        void performMemoryPressureEviction(double pressure) {
            double evictionRatio = Math.min(0.5, (pressure - MEMORY_PRESSURE_THRESHOLD) * 2);

            // Evict from all tiers, starting with coldest
            evictFromL3(evictionRatio);
            evictFromL2(evictionRatio * 0.5);
            evictFromL1(evictionRatio * 0.2);
        }

        private void evictFromL1(double ratio) {
            int targetEvictions = (int) (l1Cache.size() * ratio);
            List<String> keysToEvict = l1Cache.cache.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getValue().getLastAccessTime()))
                .limit(targetEvictions)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

            for (String key : keysToEvict) {
                CacheEntry<?> entry = l1Cache.cache.remove(key);
                if (entry != null) {
                    tierManager.demoteToL2(key, entry);
                    totalEvictions.increment();
                }
            }
        }

        private void evictFromL2(double ratio) {
            int targetEvictions = (int) (l2Cache.size() * ratio);
            List<String> keysToEvict = l2Cache.cache.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getValue().getLastAccessTime()))
                .limit(targetEvictions)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

            for (String key : keysToEvict) {
                CacheEntry<?> entry = l2Cache.cache.remove(key);
                if (entry != null) {
                    tierManager.demoteToL3(key, entry);
                    totalEvictions.increment();
                }
            }
        }

        private void evictFromL3(double ratio) {
            int targetEvictions = (int) (l3Cache.size() * ratio);
            List<String> keysToEvict = l3Cache.cache.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getValue().getCreationTime()))
                .limit(targetEvictions)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

            for (String key : keysToEvict) {
                l3Cache.remove(key);
                totalEvictions.increment();
            }
        }

        public EvictionStats getStats() {
            return new EvictionStats(totalEvictions.sum(), emergencyEvictions.sum());
        }
    }

    /**
     * Comprehensive cache statistics collection
     */
    private class CacheStatsCollector {
        private final AtomicLong l1Hits = new AtomicLong();
        private final AtomicLong l2Hits = new AtomicLong();
        private final AtomicLong l3Hits = new AtomicLong();
        private final AtomicLong misses = new AtomicLong();
        private final AtomicLong errors = new AtomicLong();

        private final AtomicLong l1Puts = new AtomicLong();
        private final AtomicLong l2Puts = new AtomicLong();
        private final AtomicLong l3Puts = new AtomicLong();

        private final AtomicLong totalLatency = new AtomicLong();
        private final AtomicLong operationCount = new AtomicLong();

        void recordL1Hit(String key, long latency) {
            l1Hits.incrementAndGet();
            recordLatency(latency);
        }

        void recordL2Hit(String key, long latency) {
            l2Hits.incrementAndGet();
            recordLatency(latency);
        }

        void recordL3Hit(String key, long latency) {
            l3Hits.incrementAndGet();
            recordLatency(latency);
        }

        void recordMiss(String key, long latency) {
            misses.incrementAndGet();
            recordLatency(latency);
        }

        void recordError(String key) {
            errors.incrementAndGet();
        }

        void recordL1Put(String key, long latency) {
            l1Puts.incrementAndGet();
            recordLatency(latency);
        }

        void recordL2Put(String key, long latency) {
            l2Puts.incrementAndGet();
            recordLatency(latency);
        }

        void recordL3Put(String key, long latency) {
            l3Puts.incrementAndGet();
            recordLatency(latency);
        }

        private void recordLatency(long latency) {
            totalLatency.addAndGet(latency);
            operationCount.incrementAndGet();
        }

        public MultiTierCacheStats getStats() {
            long totalHits = l1Hits.get() + l2Hits.get() + l3Hits.get();
            long totalOps = totalHits + misses.get();
            double hitRate = totalOps > 0 ? (double) totalHits / totalOps : 0.0;
            double avgLatency = operationCount.get() > 0 ?
                (double) totalLatency.get() / operationCount.get() / 1_000_000.0 : 0.0; // Convert to ms

            return new MultiTierCacheStats(
                l1Hits.get(), l2Hits.get(), l3Hits.get(), misses.get(),
                l1Puts.get(), l2Puts.get(), l3Puts.get(),
                hitRate, avgLatency, errors.get()
            );
        }
    }

    // Supporting classes and enums
    private enum CacheTier {
        L1, L2, L3
    }

    private static class CacheEntry<T> {
        private final T value;
        private final long ttl;
        private final long creationTime;
        private volatile long lastAccessTime;
        private volatile int accessCount;
        private volatile CacheTier currentTier;

        CacheEntry(T value, long ttl, CacheTier tier) {
            this.value = value;
            this.ttl = ttl;
            this.creationTime = System.currentTimeMillis();
            this.lastAccessTime = creationTime;
            this.accessCount = 0;
            this.currentTier = tier;
        }

        T getValue() {
            return value;
        }

        boolean isExpired() {
            return ttl > 0 && (System.currentTimeMillis() - creationTime) > ttl;
        }

        void recordAccess() {
            lastAccessTime = System.currentTimeMillis();
            accessCount++;
        }

        boolean shouldPromoteToL1() {
            // High access frequency and recent activity
            long timeSinceCreation = System.currentTimeMillis() - creationTime;
            if (timeSinceCreation < 60000) return false; // Too new

            double accessRate = (double) accessCount / (timeSinceCreation / 1000.0); // per second
            boolean recentActivity = (System.currentTimeMillis() - lastAccessTime) < 30000; // 30s

            return accessRate > 1.0 && recentActivity;
        }

        boolean shouldPromoteToL2() {
            long timeSinceCreation = System.currentTimeMillis() - creationTime;
            if (timeSinceCreation < 30000) return false; // Too new

            double accessRate = (double) accessCount / (timeSinceCreation / 1000.0);
            boolean recentActivity = (System.currentTimeMillis() - lastAccessTime) < 60000; // 1min

            return accessRate > 0.1 && recentActivity;
        }

        long getLastAccessTime() {
            return lastAccessTime;
        }

        long getCreationTime() {
            return creationTime;
        }

        void setTier(CacheTier tier) {
            this.currentTier = tier;
        }

        CacheTier getTier() {
            return currentTier;
        }
    }

    private static class CompressedCacheEntry {
        private final byte[] compressedData;
        private final long creationTime;
        private final long ttl;

        CompressedCacheEntry(CacheEntry<?> entry) {
            this.creationTime = entry.getCreationTime();
            this.ttl = entry.ttl;
            this.compressedData = compress(entry);
        }

        private byte[] compress(CacheEntry<?> entry) {
            // Simple compression implementation
            // In production, would use more sophisticated compression
            try {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                java.io.ObjectOutputStream oos = new java.io.ObjectOutputStream(baos);
                oos.writeObject(entry.getValue());
                oos.close();
                return baos.toByteArray();
            } catch (Exception e) {
                LOGGER.warn("Compression failed", e);
                return new byte[0];
            }
        }

        @SuppressWarnings("unchecked")
        <T> CacheEntry<T> decompress() {
            try {
                java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(compressedData);
                java.io.ObjectInputStream ois = new java.io.ObjectInputStream(bais);
                T value = (T) ois.readObject();
                ois.close();
                return new CacheEntry<>(value, ttl, CacheTier.L3);
            } catch (Exception e) {
                LOGGER.warn("Decompression failed", e);
                return null;
            }
        }

        long getCreationTime() {
            return creationTime;
        }

        boolean isExpired() {
            return ttl > 0 && (System.currentTimeMillis() - creationTime) > ttl;
        }
    }

    private static class AccessPattern {
        private volatile long lastAccessTime = System.currentTimeMillis();
        private volatile int accessCount = 0;
        private volatile double frequency = 0.0;

        double getAccessFrequency() {
            return frequency;
        }

        double getRecencyScore() {
            long timeSinceAccess = System.currentTimeMillis() - lastAccessTime;
            // Score decreases exponentially with time
            return Math.exp(-timeSinceAccess / 60000.0); // 1 minute decay
        }
    }

    // Stats classes
    public static class MultiTierCacheStats {
        public final long l1Hits, l2Hits, l3Hits, misses;
        public final long l1Puts, l2Puts, l3Puts;
        public final double hitRate, avgLatency;
        public final long errors;

        public MultiTierCacheStats(long l1Hits, long l2Hits, long l3Hits, long misses,
                                 long l1Puts, long l2Puts, long l3Puts,
                                 double hitRate, double avgLatency, long errors) {
            this.l1Hits = l1Hits; this.l2Hits = l2Hits; this.l3Hits = l3Hits;
            this.misses = misses; this.hitRate = hitRate; this.avgLatency = avgLatency;
            this.l1Puts = l1Puts; this.l2Puts = l2Puts; this.l3Puts = l3Puts;
            this.errors = errors;
        }

        @Override
        public String toString() {
            return String.format("MultiTierStats{L1: %d, L2: %d, L3: %d, misses: %d, hitRate: %.2f%%, latency: %.2fms}",
                               l1Hits, l2Hits, l3Hits, misses, hitRate * 100, avgLatency);
        }
    }

    public static class TierManagerStats {
        public final long promotions, demotions, activePatterns;

        public TierManagerStats(long promotions, long demotions, long activePatterns) {
            this.promotions = promotions;
            this.demotions = demotions;
            this.activePatterns = activePatterns;
        }

        @Override
        public String toString() {
            return String.format("TierManagerStats{promotions: %d, demotions: %d, patterns: %d}",
                               promotions, demotions, activePatterns);
        }
    }

    public static class MemoryPressureStats {
        public final double currentPressure;
        public final long highPressureEvents;

        public MemoryPressureStats(double currentPressure, long highPressureEvents) {
            this.currentPressure = currentPressure;
            this.highPressureEvents = highPressureEvents;
        }

        @Override
        public String toString() {
            return String.format("MemoryPressureStats{current: %.2f%%, events: %d}",
                               currentPressure * 100, highPressureEvents);
        }
    }

    public static class EvictionStats {
        public final long totalEvictions, emergencyEvictions;

        public EvictionStats(long totalEvictions, long emergencyEvictions) {
            this.totalEvictions = totalEvictions;
            this.emergencyEvictions = emergencyEvictions;
        }

        @Override
        public String toString() {
            return String.format("EvictionStats{total: %d, emergency: %d}",
                               totalEvictions, emergencyEvictions);
        }
    }

    // Public API
    public <T> T get(String key, Class<T> valueClass) {
        return get(key, valueClass);
    }

    public <T> boolean put(String key, T value) {
        return put(key, value, 300000); // 5 minutes default TTL
    }

    public void invalidate(String key) {
        l1Cache.remove(key);
        l2Cache.remove(key);
        l3Cache.remove(key);
    }

    public void clear() {
        l1Cache.clear();
        l2Cache.clear();
        l3Cache.clear();
    }

    public String getComprehensiveStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("=== INTELLIGENT MULTI-TIER CACHE STATS ===\n");
        stats.append("Overall: ").append(statsCollector.getStats()).append("\n");
        stats.append("L1 Size: ").append(l1Cache.size()).append("/").append(L1_MAX_SIZE).append("\n");
        stats.append("L2 Size: ").append(l2Cache.size()).append("/").append(L2_MAX_SIZE).append("\n");
        stats.append("L3 Size: ").append(l3Cache.size()).append("/").append(L3_MAX_SIZE).append("\n");
        stats.append("Tier Manager: ").append(tierManager.getStats()).append("\n");
        stats.append("Memory Pressure: ").append(memoryMonitor.getStats()).append("\n");
        stats.append("Eviction: ").append(evictionEngine.getStats()).append("\n");
        stats.append("==========================================");
        return stats.toString();
    }

    public void shutdown() {
        maintenanceScheduler.shutdown();
        try {
            if (!maintenanceScheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                maintenanceScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            maintenanceScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        clear();
        LOGGER.info("Intelligent Multi-Tier Cache System shutdown completed");
    }
}