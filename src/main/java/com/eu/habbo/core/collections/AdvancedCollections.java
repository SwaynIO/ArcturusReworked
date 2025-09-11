package com.eu.habbo.core.collections;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

/**
 * Advanced collection implementations with intelligent performance optimization
 * Provides high-performance data structures with built-in monitoring and auto-tuning
 */
public class AdvancedCollections {
    private static final Logger LOGGER = LoggerFactory.getLogger(AdvancedCollections.class);
    
    /**
     * Self-optimizing concurrent map with adaptive resizing and performance tracking
     */
    public static class AdaptiveConcurrentMap<K, V> extends ConcurrentHashMap<K, V> {
        private final String name;
        private final LongAdder hits = new LongAdder();
        private final LongAdder misses = new LongAdder();
        private final LongAdder puts = new LongAdder();
        private final LongAdder removes = new LongAdder();
        private final AtomicLong lastOptimization = new AtomicLong(System.currentTimeMillis());
        
        // Adaptive parameters
        private volatile float loadFactor = 0.75f;
        private volatile int targetConcurrency;
        
        public AdaptiveConcurrentMap(String name, int initialCapacity) {
            super(initialCapacity);
            this.name = name;
            this.targetConcurrency = Math.max(1, Runtime.getRuntime().availableProcessors());
        }
        
        @Override
        public V get(Object key) {
            V value = super.get(key);
            if (value != null) {
                hits.increment();
            } else {
                misses.increment();
            }
            
            considerOptimization();
            return value;
        }
        
        @Override
        public V put(K key, V value) {
            puts.increment();
            V result = super.put(key, value);
            considerOptimization();
            return result;
        }
        
        @Override
        public V remove(Object key) {
            removes.increment();
            return super.remove(key);
        }
        
        private void considerOptimization() {
            long now = System.currentTimeMillis();
            if (now - lastOptimization.get() > 60000) { // Every minute
                if (lastOptimization.compareAndSet(lastOptimization.get(), now)) {
                    optimizeStructure();
                }
            }
        }
        
        private void optimizeStructure() {
            double hitRate = getHitRate();
            int currentSize = size();
            
            // Adjust load factor based on usage patterns
            if (hitRate > 0.9 && currentSize > 1000) {
                // High hit rate with large size - optimize for speed
                loadFactor = Math.max(0.5f, loadFactor - 0.05f);
            } else if (hitRate < 0.6) {
                // Low hit rate - optimize for memory
                loadFactor = Math.min(0.9f, loadFactor + 0.05f);
            }
            
            LOGGER.debug("Optimized map '{}': hitRate={:.2f}%, size={}, loadFactor={:.2f}", 
                        name, hitRate * 100, currentSize, loadFactor);
        }
        
        public double getHitRate() {
            long totalHits = hits.sum();
            long totalAccess = totalHits + misses.sum();
            return totalAccess > 0 ? (double) totalHits / totalAccess : 0.0;
        }
        
        public MapStats getStats() {
            return new MapStats(name, size(), getHitRate(), puts.sum(), removes.sum());
        }
    }
    
    /**
     * High-performance ring buffer with overflow protection
     */
    public static class AdvancedRingBuffer<T> {
        private final Object[] buffer;
        private final int capacity;
        private final AtomicInteger writeIndex = new AtomicInteger(0);
        private final AtomicInteger readIndex = new AtomicInteger(0);
        private final AtomicInteger size = new AtomicInteger(0);
        private final LongAdder overwrites = new LongAdder();
        
        public AdvancedRingBuffer(int capacity) {
            this.capacity = capacity;
            this.buffer = new Object[capacity];
        }
        
        @SuppressWarnings("unchecked")
        public boolean offer(T item) {
            int currentWriteIndex = writeIndex.get();
            int nextWriteIndex = (currentWriteIndex + 1) % capacity;
            
            // Check if buffer is full
            if (size.get() == capacity) {
                overwrites.increment();
                // Advance read index to maintain ring buffer behavior
                readIndex.compareAndSet(readIndex.get(), (readIndex.get() + 1) % capacity);
                size.decrementAndGet();
            }
            
            buffer[currentWriteIndex] = item;
            writeIndex.set(nextWriteIndex);
            size.incrementAndGet();
            
            return true;
        }
        
        @SuppressWarnings("unchecked")
        public T poll() {
            if (size.get() == 0) {
                return null;
            }
            
            int currentReadIndex = readIndex.get();
            T item = (T) buffer[currentReadIndex];
            buffer[currentReadIndex] = null; // Help GC
            
            readIndex.set((currentReadIndex + 1) % capacity);
            size.decrementAndGet();
            
            return item;
        }
        
        public int size() {
            return size.get();
        }
        
        public boolean isEmpty() {
            return size.get() == 0;
        }
        
        public boolean isFull() {
            return size.get() == capacity;
        }
        
        public long getOverwrites() {
            return overwrites.sum();
        }
        
        public double getUtilization() {
            return (double) size.get() / capacity;
        }
    }
    
    /**
     * Bloom filter for efficient membership testing
     */
    public static class OptimizedBloomFilter<T> {
        private final BitSet bitSet;
        private final int bitSetSize;
        private final int hashFunctions;
        private final LongAdder addedElements = new LongAdder();
        private final LongAdder queries = new LongAdder();
        private final LongAdder falsePositives = new LongAdder();
        
        public OptimizedBloomFilter(int expectedElements, double falsePositiveRate) {
            this.bitSetSize = calculateBitSetSize(expectedElements, falsePositiveRate);
            this.hashFunctions = calculateHashFunctions(expectedElements, bitSetSize);
            this.bitSet = new BitSet(bitSetSize);
        }
        
        private int calculateBitSetSize(int n, double p) {
            return (int) (-n * Math.log(p) / (Math.log(2) * Math.log(2)));
        }
        
        private int calculateHashFunctions(int n, int m) {
            return Math.max(1, (int) Math.round((double) m / n * Math.log(2)));
        }
        
        public void add(T element) {
            int[] hashes = getHashes(element);
            for (int hash : hashes) {
                bitSet.set(Math.abs(hash % bitSetSize));
            }
            addedElements.increment();
        }
        
        public boolean mightContain(T element) {
            queries.increment();
            int[] hashes = getHashes(element);
            
            for (int hash : hashes) {
                if (!bitSet.get(Math.abs(hash % bitSetSize))) {
                    return false; // Definitely not present
                }
            }
            
            return true; // Might be present
        }
        
        private int[] getHashes(T element) {
            int[] hashes = new int[hashFunctions];
            int hash1 = element.hashCode();
            int hash2 = hash1 >>> 16;
            
            for (int i = 0; i < hashFunctions; i++) {
                hashes[i] = hash1 + i * hash2;
            }
            
            return hashes;
        }
        
        public double getEstimatedFalsePositiveRate() {
            double ratio = (double) bitSet.cardinality() / bitSetSize;
            return Math.pow(ratio, hashFunctions);
        }
        
        public BloomFilterStats getStats() {
            return new BloomFilterStats(
                addedElements.sum(),
                queries.sum(),
                getEstimatedFalsePositiveRate(),
                (double) bitSet.cardinality() / bitSetSize
            );
        }
    }
    
    /**
     * Lock-free concurrent queue with batching capabilities
     */
    public static class BatchingQueue<T> {
        private final ConcurrentLinkedQueue<T> queue = new ConcurrentLinkedQueue<>();
        private final AtomicInteger size = new AtomicInteger(0);
        private final LongAdder totalOffers = new LongAdder();
        private final LongAdder totalPolls = new LongAdder();
        private final LongAdder batchOperations = new LongAdder();
        
        public boolean offer(T item) {
            boolean result = queue.offer(item);
            if (result) {
                size.incrementAndGet();
                totalOffers.increment();
            }
            return result;
        }
        
        public T poll() {
            T item = queue.poll();
            if (item != null) {
                size.decrementAndGet();
                totalPolls.increment();
            }
            return item;
        }
        
        public List<T> pollBatch(int maxBatchSize) {
            List<T> batch = new ArrayList<>(Math.min(maxBatchSize, size.get()));
            
            for (int i = 0; i < maxBatchSize && !queue.isEmpty(); i++) {
                T item = queue.poll();
                if (item != null) {
                    batch.add(item);
                    size.decrementAndGet();
                    totalPolls.increment();
                }
            }
            
            if (!batch.isEmpty()) {
                batchOperations.increment();
            }
            
            return batch;
        }
        
        public boolean offerBatch(Collection<T> items) {
            boolean allAdded = true;
            for (T item : items) {
                if (queue.offer(item)) {
                    size.incrementAndGet();
                    totalOffers.increment();
                } else {
                    allAdded = false;
                }
            }
            
            if (!items.isEmpty()) {
                batchOperations.increment();
            }
            
            return allAdded;
        }
        
        public int size() {
            return size.get();
        }
        
        public boolean isEmpty() {
            return queue.isEmpty();
        }
        
        public QueueStats getStats() {
            return new QueueStats(
                size.get(),
                totalOffers.sum(),
                totalPolls.sum(),
                batchOperations.sum()
            );
        }
    }
    
    /**
     * Memory-efficient string interning with LRU eviction
     */
    public static class IntelligentStringInterner {
        private final ConcurrentHashMap<String, String> internMap;
        private final LinkedHashMap<String, Long> accessOrder;
        private final int maxSize;
        private final Object lock = new Object();
        private final LongAdder hits = new LongAdder();
        private final LongAdder misses = new LongAdder();
        private final LongAdder evictions = new LongAdder();
        
        public IntelligentStringInterner(int maxSize) {
            this.maxSize = maxSize;
            this.internMap = new ConcurrentHashMap<>(maxSize);
            this.accessOrder = new LinkedHashMap<String, Long>(maxSize, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > maxSize;
                }
            };
        }
        
        public String intern(String str) {
            if (str == null) return null;
            
            String interned = internMap.get(str);
            if (interned != null) {
                hits.increment();
                updateAccess(str);
                return interned;
            }
            
            misses.increment();
            
            // Check if we need to evict
            synchronized (lock) {
                if (internMap.size() >= maxSize) {
                    evictLRU();
                }
                
                interned = internMap.putIfAbsent(str, str);
                if (interned == null) {
                    interned = str;
                    accessOrder.put(str, System.currentTimeMillis());
                } else {
                    hits.increment();
                    updateAccess(str);
                }
            }
            
            return interned;
        }
        
        private void updateAccess(String str) {
            synchronized (lock) {
                accessOrder.put(str, System.currentTimeMillis());
            }
        }
        
        private void evictLRU() {
            if (!accessOrder.isEmpty()) {
                String eldest = accessOrder.keySet().iterator().next();
                internMap.remove(eldest);
                accessOrder.remove(eldest);
                evictions.increment();
            }
        }
        
        public double getHitRate() {
            long totalHits = hits.sum();
            long totalAccess = totalHits + misses.sum();
            return totalAccess > 0 ? (double) totalHits / totalAccess : 0.0;
        }
        
        public InternerStats getStats() {
            return new InternerStats(
                internMap.size(),
                getHitRate(),
                evictions.sum(),
                hits.sum(),
                misses.sum()
            );
        }
    }
    
    // Statistics classes
    public static class MapStats {
        public final String name;
        public final int size;
        public final double hitRate;
        public final long puts;
        public final long removes;
        
        public MapStats(String name, int size, double hitRate, long puts, long removes) {
            this.name = name;
            this.size = size;
            this.hitRate = hitRate;
            this.puts = puts;
            this.removes = removes;
        }
        
        @Override
        public String toString() {
            return String.format("MapStats{name='%s', size=%d, hitRate=%.2f%%, puts=%d, removes=%d}",
                               name, size, hitRate * 100, puts, removes);
        }
    }
    
    public static class BloomFilterStats {
        public final long addedElements;
        public final long queries;
        public final double falsePositiveRate;
        public final double utilization;
        
        public BloomFilterStats(long addedElements, long queries, double falsePositiveRate, double utilization) {
            this.addedElements = addedElements;
            this.queries = queries;
            this.falsePositiveRate = falsePositiveRate;
            this.utilization = utilization;
        }
        
        @Override
        public String toString() {
            return String.format("BloomFilterStats{elements=%d, queries=%d, fpRate=%.4f%%, util=%.2f%%}",
                               addedElements, queries, falsePositiveRate * 100, utilization * 100);
        }
    }
    
    public static class QueueStats {
        public final int currentSize;
        public final long totalOffers;
        public final long totalPolls;
        public final long batchOperations;
        
        public QueueStats(int currentSize, long totalOffers, long totalPolls, long batchOperations) {
            this.currentSize = currentSize;
            this.totalOffers = totalOffers;
            this.totalPolls = totalPolls;
            this.batchOperations = batchOperations;
        }
        
        @Override
        public String toString() {
            return String.format("QueueStats{size=%d, offers=%d, polls=%d, batches=%d}",
                               currentSize, totalOffers, totalPolls, batchOperations);
        }
    }
    
    public static class InternerStats {
        public final int size;
        public final double hitRate;
        public final long evictions;
        public final long hits;
        public final long misses;
        
        public InternerStats(int size, double hitRate, long evictions, long hits, long misses) {
            this.size = size;
            this.hitRate = hitRate;
            this.evictions = evictions;
            this.hits = hits;
            this.misses = misses;
        }
        
        @Override
        public String toString() {
            return String.format("InternerStats{size=%d, hitRate=%.2f%%, evictions=%d, hits=%d, misses=%d}",
                               size, hitRate * 100, evictions, hits, misses);
        }
    }
}