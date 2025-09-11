package com.eu.habbo.core.compression;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.zip.*;

/**
 * Dynamic compression engine with adaptive algorithm selection and intelligent optimization
 * Automatically selects the best compression algorithm based on data characteristics
 */
public class DynamicCompressionEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(DynamicCompressionEngine.class);
    
    private static DynamicCompressionEngine instance;
    
    private final CompressionAlgorithmSelector algorithmSelector;
    private final CompressionProfiler profiler;
    private final AdaptiveCompressionCache cache;
    private final ExecutorService compressionPool;
    
    // Configuration
    private static final int MIN_COMPRESSION_SIZE = 128;
    private static final double MIN_COMPRESSION_RATIO = 0.8;
    private static final int CACHE_SIZE = 1000;
    
    private DynamicCompressionEngine() {
        this.algorithmSelector = new CompressionAlgorithmSelector();
        this.profiler = new CompressionProfiler();
        this.cache = new AdaptiveCompressionCache(CACHE_SIZE);
        
        this.compressionPool = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2),
            r -> {
                Thread t = new Thread(r, "DynamicCompression");
                t.setDaemon(true);
                return t;
            }
        );
        
        LOGGER.info("Dynamic Compression Engine initialized with {} threads", 
                   Runtime.getRuntime().availableProcessors() / 2);
    }
    
    public static synchronized DynamicCompressionEngine getInstance() {
        if (instance == null) {
            instance = new DynamicCompressionEngine();
        }
        return instance;
    }
    
    /**
     * Compress data with automatic algorithm selection
     */
    public CompletableFuture<CompressionResult> compressAsync(byte[] data, String context) {
        return CompletableFuture.supplyAsync(() -> {
            if (data.length < MIN_COMPRESSION_SIZE) {
                return new CompressionResult(data, CompressionAlgorithm.NONE, 0, 0, 1.0);
            }
            
            // Check cache first
            String cacheKey = generateCacheKey(data, context);
            CompressionResult cached = cache.get(cacheKey);
            if (cached != null) {
                profiler.recordCacheHit();
                return cached;
            }
            
            profiler.recordCacheMiss();
            
            // Profile data characteristics
            DataProfile profile = profiler.profileData(data, context);
            
            // Select best algorithm
            CompressionAlgorithm algorithm = algorithmSelector.selectAlgorithm(profile);
            
            // Perform compression
            long startTime = System.nanoTime();
            CompressionResult result = performCompression(data, algorithm, profile);
            long compressionTime = System.nanoTime() - startTime;
            
            result.compressionTimeNanos = compressionTime;
            
            // Update algorithm statistics
            algorithmSelector.recordResult(algorithm, profile, result);
            
            // Cache result if beneficial
            if (result.compressionRatio < MIN_COMPRESSION_RATIO) {
                cache.put(cacheKey, result);
            }
            
            return result;
            
        }, compressionPool);
    }
    
    /**
     * Synchronous compression method
     */
    public CompressionResult compress(byte[] data, String context) {
        try {
            return compressAsync(data, context).get();
        } catch (InterruptedException | ExecutionException e) {
            LOGGER.error("Compression failed", e);
            return new CompressionResult(data, CompressionAlgorithm.NONE, 0, 0, 1.0);
        }
    }
    
    /**
     * Decompress data
     */
    public byte[] decompress(byte[] compressedData, CompressionAlgorithm algorithm) {
        try {
            switch (algorithm) {
                case DEFLATE:
                    return decompressDeflate(compressedData);
                case GZIP:
                    return decompressGzip(compressedData);
                case LZ4_FAST:
                case LZ4_HIGH:
                    return decompressLZ4(compressedData);
                case SNAPPY:
                    return decompressSnappy(compressedData);
                case NONE:
                default:
                    return compressedData;
            }
        } catch (Exception e) {
            LOGGER.error("Decompression failed for algorithm: {}", algorithm, e);
            return compressedData;
        }
    }
    
    private CompressionResult performCompression(byte[] data, CompressionAlgorithm algorithm, DataProfile profile) {
        try {
            switch (algorithm) {
                case DEFLATE:
                    return compressDeflate(data, profile.isHighEntropy() ? Deflater.BEST_SPEED : Deflater.BEST_COMPRESSION);
                case GZIP:
                    return compressGzip(data);
                case LZ4_FAST:
                    return compressLZ4Fast(data);
                case LZ4_HIGH:
                    return compressLZ4High(data);
                case SNAPPY:
                    return compressSnappy(data);
                case NONE:
                default:
                    return new CompressionResult(data, CompressionAlgorithm.NONE, 0, 0, 1.0);
            }
        } catch (Exception e) {
            LOGGER.error("Compression failed for algorithm: {}", algorithm, e);
            return new CompressionResult(data, CompressionAlgorithm.NONE, 0, 0, 1.0);
        }
    }
    
    private CompressionResult compressDeflate(byte[] data, int level) throws IOException {
        Deflater deflater = new Deflater(level);
        try {
            deflater.setInput(data);
            deflater.finish();
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                baos.write(buffer, 0, count);
            }
            
            byte[] compressed = baos.toByteArray();
            double ratio = (double) compressed.length / data.length;
            
            return new CompressionResult(
                compressed, CompressionAlgorithm.DEFLATE,
                data.length, compressed.length, ratio
            );
        } finally {
            deflater.end();
        }
    }
    
    private CompressionResult compressGzip(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(data);
        }
        
        byte[] compressed = baos.toByteArray();
        double ratio = (double) compressed.length / data.length;
        
        return new CompressionResult(
            compressed, CompressionAlgorithm.GZIP,
            data.length, compressed.length, ratio
        );
    }
    
    private CompressionResult compressLZ4Fast(byte[] data) {
        // Placeholder for LZ4 fast compression
        // In real implementation, would use LZ4 library
        return compressDeflateWrapper(data, CompressionAlgorithm.LZ4_FAST, Deflater.BEST_SPEED);
    }
    
    private CompressionResult compressLZ4High(byte[] data) {
        // Placeholder for LZ4 high compression
        return compressDeflateWrapper(data, CompressionAlgorithm.LZ4_HIGH, Deflater.BEST_COMPRESSION);
    }
    
    private CompressionResult compressSnappy(byte[] data) {
        // Placeholder for Snappy compression
        return compressDeflateWrapper(data, CompressionAlgorithm.SNAPPY, Deflater.DEFAULT_COMPRESSION);
    }
    
    private CompressionResult compressDeflateWrapper(byte[] data, CompressionAlgorithm algorithm, int level) {
        try {
            return compressDeflate(data, level);
        } catch (IOException e) {
            LOGGER.error("Compression failed", e);
            return new CompressionResult(data, CompressionAlgorithm.NONE, 0, 0, 1.0);
        }
    }
    
    private byte[] decompressDeflate(byte[] data) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                baos.write(buffer, 0, count);
            }
            
            return baos.toByteArray();
        } catch (DataFormatException e) {
            throw new IOException("Invalid compressed data", e);
        } finally {
            inflater.end();
        }
    }
    
    private byte[] decompressGzip(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPInputStream gzis = new GZIPInputStream(new java.io.ByteArrayInputStream(data))) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = gzis.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
        }
        return baos.toByteArray();
    }
    
    private byte[] decompressLZ4(byte[] data) throws IOException {
        // Placeholder - would use actual LZ4 decompression
        return decompressDeflate(data);
    }
    
    private byte[] decompressSnappy(byte[] data) throws IOException {
        // Placeholder - would use actual Snappy decompression
        return decompressDeflate(data);
    }
    
    private String generateCacheKey(byte[] data, String context) {
        // Simple hash-based cache key
        return String.format("%s_%d_%d", context, data.length, java.util.Arrays.hashCode(data));
    }
    
    /**
     * Intelligent algorithm selection based on data characteristics
     */
    private class CompressionAlgorithmSelector {
        private final Map<CompressionAlgorithm, AlgorithmStats> stats = new ConcurrentHashMap<>();
        
        CompressionAlgorithmSelector() {
            for (CompressionAlgorithm algorithm : CompressionAlgorithm.values()) {
                stats.put(algorithm, new AlgorithmStats());
            }
        }
        
        CompressionAlgorithm selectAlgorithm(DataProfile profile) {
            // Rule-based selection with machine learning potential
            
            if (profile.size < 512) {
                // Small data - fast compression
                return CompressionAlgorithm.LZ4_FAST;
            }
            
            if (profile.isHighEntropy()) {
                // High entropy data compresses poorly - use fast algorithm
                return CompressionAlgorithm.LZ4_FAST;
            }
            
            if (profile.isRepeatingPattern()) {
                // Repeating patterns compress well - use high compression
                return CompressionAlgorithm.DEFLATE;
            }
            
            if (profile.context != null && profile.context.contains("json")) {
                // JSON data - good compression with GZIP
                return CompressionAlgorithm.GZIP;
            }
            
            // Default to balanced approach
            return getBestPerformingAlgorithm(profile.size);
        }
        
        private CompressionAlgorithm getBestPerformingAlgorithm(int dataSize) {
            CompressionAlgorithm best = CompressionAlgorithm.DEFLATE;
            double bestScore = 0.0;
            
            for (Map.Entry<CompressionAlgorithm, AlgorithmStats> entry : stats.entrySet()) {
                AlgorithmStats stat = entry.getValue();
                if (stat.totalCompressions > 10) { // Need enough samples
                    double score = calculateAlgorithmScore(stat, dataSize);
                    if (score > bestScore) {
                        best = entry.getKey();
                        bestScore = score;
                    }
                }
            }
            
            return best;
        }
        
        private double calculateAlgorithmScore(AlgorithmStats stats, int dataSize) {
            // Weighted score: compression ratio (70%) + speed (30%)
            double avgRatio = stats.totalCompressionRatio / stats.totalCompressions;
            double avgSpeed = stats.totalCompressionTime / stats.totalCompressions;
            
            // Normalize speed (lower is better)
            double speedScore = Math.max(0.0, 1.0 - (avgSpeed / 1_000_000.0)); // nanoseconds to score
            
            return (1.0 - avgRatio) * 0.7 + speedScore * 0.3;
        }
        
        void recordResult(CompressionAlgorithm algorithm, DataProfile profile, CompressionResult result) {
            AlgorithmStats stat = stats.get(algorithm);
            stat.totalCompressions++;
            stat.totalCompressionRatio += result.compressionRatio;
            stat.totalCompressionTime += result.compressionTimeNanos;
            stat.totalOriginalSize += result.originalSize;
            stat.totalCompressedSize += result.compressedSize;
        }
    }
    
    /**
     * Data profiler for analyzing compression characteristics
     */
    private class CompressionProfiler {
        private final LongAdder cacheHits = new LongAdder();
        private final LongAdder cacheMisses = new LongAdder();
        
        DataProfile profileData(byte[] data, String context) {
            // Analyze data characteristics
            double entropy = calculateEntropy(data);
            boolean repeatingPattern = hasRepeatingPattern(data);
            int distinctBytes = countDistinctBytes(data);
            
            return new DataProfile(data.length, entropy, repeatingPattern, distinctBytes, context);
        }
        
        private double calculateEntropy(byte[] data) {
            int[] frequencies = new int[256];
            for (byte b : data) {
                frequencies[b & 0xFF]++;
            }
            
            double entropy = 0.0;
            int length = data.length;
            
            for (int freq : frequencies) {
                if (freq > 0) {
                    double probability = (double) freq / length;
                    entropy -= probability * (Math.log(probability) / Math.log(2));
                }
            }
            
            return entropy;
        }
        
        private boolean hasRepeatingPattern(byte[] data) {
            // Simple pattern detection - check for repeated sequences
            if (data.length < 16) return false;
            
            Map<String, Integer> patterns = new HashMap<>();
            int patternLength = Math.min(8, data.length / 4);
            
            for (int i = 0; i <= data.length - patternLength; i++) {
                String pattern = java.util.Arrays.toString(
                    java.util.Arrays.copyOfRange(data, i, i + patternLength));
                patterns.put(pattern, patterns.getOrDefault(pattern, 0) + 1);
            }
            
            // If any pattern appears more than 10% of possible times
            int threshold = Math.max(1, (data.length - patternLength + 1) / 10);
            return patterns.values().stream().anyMatch(count -> count > threshold);
        }
        
        private int countDistinctBytes(byte[] data) {
            boolean[] seen = new boolean[256];
            int distinct = 0;
            
            for (byte b : data) {
                int index = b & 0xFF;
                if (!seen[index]) {
                    seen[index] = true;
                    distinct++;
                }
            }
            
            return distinct;
        }
        
        void recordCacheHit() { cacheHits.increment(); }
        void recordCacheMiss() { cacheMisses.increment(); }
        
        public double getCacheHitRate() {
            long hits = cacheHits.sum();
            long total = hits + cacheMisses.sum();
            return total > 0 ? (double) hits / total : 0.0;
        }
    }
    
    /**
     * Adaptive compression result cache
     */
    private class AdaptiveCompressionCache {
        private final ConcurrentHashMap<String, CacheEntry> cache;
        private final int maxSize;
        private final AtomicLong accessCounter = new AtomicLong();
        
        AdaptiveCompressionCache(int maxSize) {
            this.maxSize = maxSize;
            this.cache = new ConcurrentHashMap<>(maxSize);
        }
        
        CompressionResult get(String key) {
            CacheEntry entry = cache.get(key);
            if (entry != null) {
                entry.lastAccess = accessCounter.incrementAndGet();
                return entry.result;
            }
            return null;
        }
        
        void put(String key, CompressionResult result) {
            if (cache.size() >= maxSize) {
                evictLRU();
            }
            
            cache.put(key, new CacheEntry(result, accessCounter.incrementAndGet()));
        }
        
        private void evictLRU() {
            String lruKey = null;
            long oldestAccess = Long.MAX_VALUE;
            
            for (Map.Entry<String, CacheEntry> entry : cache.entrySet()) {
                if (entry.getValue().lastAccess < oldestAccess) {
                    oldestAccess = entry.getValue().lastAccess;
                    lruKey = entry.getKey();
                }
            }
            
            if (lruKey != null) {
                cache.remove(lruKey);
            }
        }
        
        private class CacheEntry {
            final CompressionResult result;
            volatile long lastAccess;
            
            CacheEntry(CompressionResult result, long lastAccess) {
                this.result = result;
                this.lastAccess = lastAccess;
            }
        }
    }
    
    // Data classes
    public static class CompressionResult {
        public final byte[] data;
        public final CompressionAlgorithm algorithm;
        public final int originalSize;
        public final int compressedSize;
        public final double compressionRatio;
        public long compressionTimeNanos;
        
        public CompressionResult(byte[] data, CompressionAlgorithm algorithm, 
                               int originalSize, int compressedSize, double compressionRatio) {
            this.data = data;
            this.algorithm = algorithm;
            this.originalSize = originalSize;
            this.compressedSize = compressedSize;
            this.compressionRatio = compressionRatio;
        }
        
        @Override
        public String toString() {
            return String.format("CompressionResult{algorithm=%s, ratio=%.2f, time=%.2fms}",
                               algorithm, compressionRatio, compressionTimeNanos / 1_000_000.0);
        }
    }
    
    private static class DataProfile {
        final int size;
        final double entropy;
        final boolean repeatingPattern;
        final int distinctBytes;
        final String context;
        
        DataProfile(int size, double entropy, boolean repeatingPattern, int distinctBytes, String context) {
            this.size = size;
            this.entropy = entropy;
            this.repeatingPattern = repeatingPattern;
            this.distinctBytes = distinctBytes;
            this.context = context;
        }
        
        boolean isHighEntropy() {
            return entropy > 7.0; // Close to maximum entropy of 8.0
        }
        
        boolean isRepeatingPattern() {
            return repeatingPattern;
        }
    }
    
    private static class AlgorithmStats {
        long totalCompressions = 0;
        double totalCompressionRatio = 0.0;
        long totalCompressionTime = 0;
        long totalOriginalSize = 0;
        long totalCompressedSize = 0;
    }
    
    public enum CompressionAlgorithm {
        NONE, DEFLATE, GZIP, LZ4_FAST, LZ4_HIGH, SNAPPY
    }
    
    // Public API methods
    public CompressionStats getStats() {
        return new CompressionStats(
            profiler.getCacheHitRate(),
            algorithmSelector.stats.values().stream()
                .mapToLong(stats -> stats.totalCompressions)
                .sum()
        );
    }
    
    public static class CompressionStats {
        public final double cacheHitRate;
        public final long totalCompressions;
        
        public CompressionStats(double cacheHitRate, long totalCompressions) {
            this.cacheHitRate = cacheHitRate;
            this.totalCompressions = totalCompressions;
        }
        
        @Override
        public String toString() {
            return String.format("CompressionStats{cacheHitRate=%.2f%%, totalCompressions=%d}",
                               cacheHitRate * 100, totalCompressions);
        }
    }
    
    public void shutdown() {
        compressionPool.shutdown();
        try {
            if (!compressionPool.awaitTermination(30, TimeUnit.SECONDS)) {
                compressionPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            compressionPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        LOGGER.info("Dynamic Compression Engine shutdown completed");
    }
}