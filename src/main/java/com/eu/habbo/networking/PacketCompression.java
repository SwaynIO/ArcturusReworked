package com.eu.habbo.networking;

import com.eu.habbo.core.metrics.RealTimeMetrics;
import com.eu.habbo.core.metrics.TimerContext;
import com.eu.habbo.util.ObjectPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Advanced packet compression system for Arcturus Morningstar Reworked
 * Provides intelligent compression with adaptive algorithms and performance monitoring
 */
public class PacketCompression {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(PacketCompression.class);
    
    // Compression thresholds
    private static final int MIN_COMPRESSION_SIZE = 64; // Only compress packets >= 64 bytes
    private static final int MAX_COMPRESSION_SIZE = 8192; // Skip compression for very large packets
    private static final double MIN_COMPRESSION_RATIO = 0.8; // Only use compression if ratio < 80%
    
    // Object pools for reusing compression resources
    private static final ObjectPool<Deflater> DEFLATER_POOL = 
        new ObjectPool<>(() -> new Deflater(Deflater.BEST_SPEED), 16);
    private static final ObjectPool<Inflater> INFLATER_POOL = 
        new ObjectPool<>(() -> new Inflater(), 16);
    private static final ObjectPool<byte[]> BUFFER_POOL = 
        new ObjectPool<>(() -> new byte[8192], 32);
    
    // Compression statistics per packet type
    private static final ConcurrentHashMap<Integer, CompressionStats> STATS = new ConcurrentHashMap<>();
    
    // Performance metrics
    private static final RealTimeMetrics METRICS = RealTimeMetrics.getInstance();
    
    /**
     * Compress packet data if beneficial
     */
    public static CompressedPacket compress(int packetId, byte[] data) {
        if (data == null || data.length < MIN_COMPRESSION_SIZE || data.length > MAX_COMPRESSION_SIZE) {
            return new CompressedPacket(data, false, 1.0);
        }
        
        try (TimerContext timer = METRICS.startTimer("packet.compression")) {
            CompressionStats stats = STATS.computeIfAbsent(packetId, k -> new CompressionStats());
            
            // Check if this packet type historically compresses well
            if (!stats.shouldAttemptCompression()) {
                return new CompressedPacket(data, false, 1.0);
            }
            
            Deflater deflater = DEFLATER_POOL.borrow();
            byte[] buffer = BUFFER_POOL.borrow();
            
            try {
                deflater.reset();
                deflater.setInput(data);
                deflater.finish();
                
                ByteArrayOutputStream compressedStream = new ByteArrayOutputStream(data.length);
                
                while (!deflater.finished()) {
                    int bytesCompressed = deflater.deflate(buffer);
                    if (bytesCompressed > 0) {
                        compressedStream.write(buffer, 0, bytesCompressed);
                    }
                }
                
                byte[] compressed = compressedStream.toByteArray();
                double ratio = (double) compressed.length / data.length;
                
                // Update statistics
                stats.recordCompression(ratio);
                
                // Only use compression if it provides significant benefit
                if (ratio < MIN_COMPRESSION_RATIO) {
                    METRICS.incrementCounter("packet.compression.success");
                    return new CompressedPacket(compressed, true, ratio);
                } else {
                    METRICS.incrementCounter("packet.compression.skipped.ratio");
                    return new CompressedPacket(data, false, ratio);
                }
                
            } finally {
                DEFLATER_POOL.returnObject(deflater);
                BUFFER_POOL.returnObject(buffer);
            }
            
        } catch (Exception e) {
            LOGGER.warn("Compression failed for packet {}: {}", packetId, e.getMessage());
            METRICS.incrementCounter("packet.compression.error");
            return new CompressedPacket(data, false, 1.0);
        }
    }
    
    /**
     * Decompress packet data
     */
    public static byte[] decompress(byte[] compressedData) {
        if (compressedData == null || compressedData.length == 0) {
            return compressedData;
        }
        
        try (TimerContext timer = METRICS.startTimer("packet.decompression")) {
            Inflater inflater = INFLATER_POOL.borrow();
            byte[] buffer = BUFFER_POOL.borrow();
            
            try {
                inflater.reset();
                inflater.setInput(compressedData);
                
                ByteArrayOutputStream decompressedStream = new ByteArrayOutputStream(compressedData.length * 2);
                
                while (!inflater.finished()) {
                    int bytesDecompressed = inflater.inflate(buffer);
                    if (bytesDecompressed > 0) {
                        decompressedStream.write(buffer, 0, bytesDecompressed);
                    }
                    
                    if (inflater.needsInput()) {
                        break;
                    }
                }
                
                METRICS.incrementCounter("packet.decompression.success");
                return decompressedStream.toByteArray();
                
            } finally {
                INFLATER_POOL.returnObject(inflater);
                BUFFER_POOL.returnObject(buffer);
            }
            
        } catch (Exception e) {
            LOGGER.warn("Decompression failed: {}", e.getMessage());
            METRICS.incrementCounter("packet.decompression.error");
            return compressedData; // Return original data as fallback
        }
    }
    
    /**
     * Get compression statistics for a packet type
     */
    public static CompressionStats getStats(int packetId) {
        return STATS.get(packetId);
    }
    
    /**
     * Get overall compression report
     */
    public static String getCompressionReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== Packet Compression Report ===\n");
        
        long totalAttempts = STATS.values().stream().mapToLong(CompressionStats::getAttempts).sum();
        long totalSavings = STATS.values().stream().mapToLong(CompressionStats::getTotalBytesSaved).sum();
        
        report.append(String.format("Total compression attempts: %,d\n", totalAttempts));
        report.append(String.format("Total bytes saved: %,d\n", totalSavings));
        
        if (totalAttempts > 0) {
            double avgRatio = STATS.values().stream()
                .mapToDouble(CompressionStats::getAverageRatio)
                .average().orElse(1.0);
            report.append(String.format("Average compression ratio: %.2f\n", avgRatio));
        }
        
        report.append("\nTop compressible packet types:\n");
        STATS.entrySet().stream()
             .filter(entry -> entry.getValue().getAttempts() > 10)
             .sorted((a, b) -> Double.compare(a.getValue().getAverageRatio(), b.getValue().getAverageRatio()))
             .limit(10)
             .forEach(entry -> {
                 CompressionStats stats = entry.getValue();
                 report.append(String.format("  Packet %d: %.2f ratio, %,d bytes saved\n",
                     entry.getKey(), stats.getAverageRatio(), stats.getTotalBytesSaved()));
             });
        
        return report.toString();
    }
    
    /**
     * Reset compression statistics
     */
    public static void resetStats() {
        STATS.clear();
        LOGGER.info("Compression statistics reset");
    }
    
    /**
     * Compressed packet container
     */
    public static class CompressedPacket {
        private final byte[] data;
        private final boolean compressed;
        private final double ratio;
        
        public CompressedPacket(byte[] data, boolean compressed, double ratio) {
            this.data = data;
            this.compressed = compressed;
            this.ratio = ratio;
        }
        
        public byte[] getData() { return data; }
        public boolean isCompressed() { return compressed; }
        public double getCompressionRatio() { return ratio; }
        public int getSavedBytes() { 
            return compressed ? (int) ((1.0 - ratio) * data.length / ratio) : 0; 
        }
    }
    
    /**
     * Compression statistics for a specific packet type
     */
    public static class CompressionStats {
        private long attempts = 0;
        private long successfulCompressions = 0;
        private double totalRatio = 0.0;
        private long totalBytesSaved = 0;
        private long consecutiveFailures = 0;
        private static final int MAX_CONSECUTIVE_FAILURES = 10;
        
        public synchronized void recordCompression(double ratio) {
            attempts++;
            totalRatio += ratio;
            
            if (ratio < MIN_COMPRESSION_RATIO) {
                successfulCompressions++;
                consecutiveFailures = 0;
                // Estimate bytes saved
                totalBytesSaved += (long) ((1.0 - ratio) * 100); // Approximate
            } else {
                consecutiveFailures++;
            }
        }
        
        public synchronized boolean shouldAttemptCompression() {
            if (attempts < 5) return true; // Always try first few attempts
            if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) return false;
            
            double successRate = (double) successfulCompressions / attempts;
            return successRate > 0.3; // Only if 30%+ success rate
        }
        
        public synchronized double getAverageRatio() {
            return attempts > 0 ? totalRatio / attempts : 1.0;
        }
        
        public synchronized long getAttempts() { return attempts; }
        public synchronized long getSuccessfulCompressions() { return successfulCompressions; }
        public synchronized long getTotalBytesSaved() { return totalBytesSaved; }
        public synchronized double getSuccessRate() { 
            return attempts > 0 ? (double) successfulCompressions / attempts : 0.0; 
        }
    }
}