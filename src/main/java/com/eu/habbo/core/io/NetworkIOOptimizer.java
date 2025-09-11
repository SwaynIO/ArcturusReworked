package com.eu.habbo.core.io;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Advanced network I/O optimization using NIO.2 with intelligent packet batching
 */
public class NetworkIOOptimizer {
    private static final Logger LOGGER = LoggerFactory.getLogger(NetworkIOOptimizer.class);
    
    private static NetworkIOOptimizer instance;
    private final AsynchronousServerSocketChannel serverChannel;
    private final AsynchronousChannelGroup channelGroup;
    private final ByteBufAllocator bufferAllocator;
    private final PacketBatcher packetBatcher;
    private final NetworkMetrics metrics;
    private final ScheduledExecutorService scheduler;
    
    // Network configuration
    private static final int SO_RCVBUF = 65536;
    private static final int SO_SNDBUF = 65536;
    private static final boolean TCP_NODELAY = true;
    private static final int BATCH_SIZE = 32;
    private static final long BATCH_TIMEOUT_MS = 5;
    
    private NetworkIOOptimizer() throws IOException {
        this.channelGroup = AsynchronousChannelGroup.withFixedThreadPool(
            Math.max(4, Runtime.getRuntime().availableProcessors() * 2),
            Executors.defaultThreadFactory()
        );
        
        this.serverChannel = AsynchronousServerSocketChannel.open(channelGroup);
        this.bufferAllocator = PooledByteBufAllocator.DEFAULT;
        this.packetBatcher = new PacketBatcher();
        this.metrics = new NetworkMetrics();
        
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "NetworkIO-Scheduler");
            t.setDaemon(true);
            return t;
        });
        
        // Configure socket options for optimal performance
        configureServerSocket();
        
        LOGGER.info("NetworkIOOptimizer initialized with {} threads", 
                   Runtime.getRuntime().availableProcessors() * 2);
    }
    
    public static synchronized NetworkIOOptimizer getInstance() throws IOException {
        if (instance == null) {
            instance = new NetworkIOOptimizer();
        }
        return instance;
    }
    
    private void configureServerSocket() throws IOException {
        serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        serverChannel.setOption(StandardSocketOptions.SO_RCVBUF, SO_RCVBUF);
    }
    
    public void bind(int port) throws IOException {
        serverChannel.bind(new InetSocketAddress(port));
        LOGGER.info("NetworkIOOptimizer bound to port {}", port);
        startAccepting();
    }
    
    private void startAccepting() {
        serverChannel.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
            @Override
            public void completed(AsynchronousSocketChannel clientChannel, Void attachment) {
                // Continue accepting new connections
                serverChannel.accept(null, this);
                
                // Handle the new client
                handleNewClient(clientChannel);
            }
            
            @Override
            public void failed(Throwable exc, Void attachment) {
                LOGGER.error("Failed to accept connection", exc);
                metrics.recordError();
                // Continue accepting despite the error
                serverChannel.accept(null, this);
            }
        });
    }
    
    private void handleNewClient(AsynchronousSocketChannel clientChannel) {
        try {
            // Configure client socket for optimal performance
            clientChannel.setOption(StandardSocketOptions.TCP_NODELAY, TCP_NODELAY);
            clientChannel.setOption(StandardSocketOptions.SO_RCVBUF, SO_RCVBUF);
            clientChannel.setOption(StandardSocketOptions.SO_SNDBUF, SO_SNDBUF);
            clientChannel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
            
            ClientHandler handler = new ClientHandler(clientChannel);
            handler.startReading();
            
            metrics.recordConnection();
            
        } catch (IOException e) {
            LOGGER.error("Failed to configure client socket", e);
            try {
                clientChannel.close();
            } catch (IOException closeEx) {
                LOGGER.debug("Error closing failed client channel", closeEx);
            }
        }
    }
    
    /**
     * High-performance packet batching for reduced system calls
     */
    public void sendBatched(AsynchronousSocketChannel channel, byte[]... packets) {
        packetBatcher.addBatch(channel, packets);
    }
    
    /**
     * Single packet send with automatic batching consideration
     */
    public CompletableFuture<Integer> sendAsync(AsynchronousSocketChannel channel, byte[] data) {
        if (packetBatcher.shouldBatch(channel)) {
            packetBatcher.add(channel, data);
            return CompletableFuture.completedFuture(data.length);
        }
        
        return performSend(channel, data);
    }
    
    private CompletableFuture<Integer> performSend(AsynchronousSocketChannel channel, byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        CompletableFuture<Integer> future = new CompletableFuture<>();
        
        channel.write(buffer, buffer, new CompletionHandler<Integer, ByteBuffer>() {
            @Override
            public void completed(Integer result, ByteBuffer attachment) {
                metrics.recordBytesSent(result);
                future.complete(result);
            }
            
            @Override
            public void failed(Throwable exc, ByteBuffer attachment) {
                metrics.recordError();
                LOGGER.debug("Send failed", exc);
                future.completeExceptionally(exc);
            }
        });
        
        return future;
    }
    
    /**
     * Intelligent packet batching system
     */
    private class PacketBatcher {
        private final ConcurrentHashMap<AsynchronousSocketChannel, ChannelBatch> batches = new ConcurrentHashMap<>();
        
        void add(AsynchronousSocketChannel channel, byte[] data) {
            ChannelBatch batch = batches.computeIfAbsent(channel, k -> new ChannelBatch(k));
            batch.add(data);
        }
        
        void addBatch(AsynchronousSocketChannel channel, byte[]... packets) {
            ChannelBatch batch = batches.computeIfAbsent(channel, k -> new ChannelBatch(k));
            for (byte[] packet : packets) {
                batch.add(packet);
            }
        }
        
        boolean shouldBatch(AsynchronousSocketChannel channel) {
            ChannelBatch batch = batches.get(channel);
            return batch != null && batch.size() < BATCH_SIZE;
        }
        
        private class ChannelBatch {
            private final AsynchronousSocketChannel channel;
            private final ConcurrentLinkedQueue<byte[]> packets = new ConcurrentLinkedQueue<>();
            private final AtomicInteger size = new AtomicInteger();
            private volatile long lastActivity = System.currentTimeMillis();
            
            ChannelBatch(AsynchronousSocketChannel channel) {
                this.channel = channel;
                
                // Schedule periodic flush
                scheduler.scheduleWithFixedDelay(this::flushIfNeeded, 
                    BATCH_TIMEOUT_MS, BATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            }
            
            void add(byte[] data) {
                packets.offer(data);
                size.incrementAndGet();
                lastActivity = System.currentTimeMillis();
                
                if (size.get() >= BATCH_SIZE) {
                    flush();
                }
            }
            
            int size() {
                return size.get();
            }
            
            private void flushIfNeeded() {
                if (size.get() > 0 && (System.currentTimeMillis() - lastActivity) >= BATCH_TIMEOUT_MS) {
                    flush();
                }
            }
            
            private void flush() {
                if (size.get() == 0) return;
                
                // Collect all packets
                int totalSize = 0;
                ByteBuffer[] buffers = new ByteBuffer[size.get()];
                int index = 0;
                
                byte[] packet;
                while ((packet = packets.poll()) != null && index < buffers.length) {
                    buffers[index] = ByteBuffer.wrap(packet);
                    totalSize += packet.length;
                    index++;
                }
                
                if (index > 0) {
                    // Perform gathered write for optimal network utilization
                    performGatheredWrite(channel, buffers, index, totalSize);
                    size.set(0);
                }
            }
        }
        
        private void performGatheredWrite(AsynchronousSocketChannel channel, ByteBuffer[] buffers, 
                                        int count, int totalSize) {
            channel.write(buffers, 0, count, 30L, TimeUnit.SECONDS, null,
                new CompletionHandler<Long, Void>() {
                    @Override
                    public void completed(Long result, Void attachment) {
                        metrics.recordBytesSent(result.intValue());
                        metrics.recordBatchSend(count);
                    }
                    
                    @Override
                    public void failed(Throwable exc, Void attachment) {
                        metrics.recordError();
                        LOGGER.debug("Batched write failed", exc);
                    }
                });
        }
    }
    
    /**
     * Client connection handler with optimized read operations
     */
    private class ClientHandler {
        private final AsynchronousSocketChannel channel;
        private final ByteBuffer readBuffer;
        
        ClientHandler(AsynchronousSocketChannel channel) {
            this.channel = channel;
            this.readBuffer = ByteBuffer.allocateDirect(8192);
        }
        
        void startReading() {
            channel.read(readBuffer, this, new CompletionHandler<Integer, ClientHandler>() {
                @Override
                public void completed(Integer result, ClientHandler handler) {
                    if (result > 0) {
                        metrics.recordBytesReceived(result);
                        
                        // Process received data
                        readBuffer.flip();
                        processReceivedData(readBuffer);
                        readBuffer.clear();
                        
                        // Continue reading
                        channel.read(readBuffer, handler, this);
                    } else if (result == -1) {
                        // Connection closed
                        handleDisconnection();
                    }
                }
                
                @Override
                public void failed(Throwable exc, ClientHandler handler) {
                    LOGGER.debug("Read failed", exc);
                    metrics.recordError();
                    handleDisconnection();
                }
            });
        }
        
        private void processReceivedData(ByteBuffer buffer) {
            // Process the received data here
            // This would integrate with the existing packet processing system
            int dataLength = buffer.remaining();
            byte[] data = new byte[dataLength];
            buffer.get(data);
            
            // Forward to existing packet processing
            metrics.recordPacketReceived();
        }
        
        private void handleDisconnection() {
            try {
                channel.close();
                metrics.recordDisconnection();
            } catch (IOException e) {
                LOGGER.debug("Error closing channel", e);
            }
        }
    }
    
    /**
     * Comprehensive network performance metrics
     */
    public static class NetworkMetrics {
        private final LongAdder connections = new LongAdder();
        private final LongAdder disconnections = new LongAdder();
        private final LongAdder bytesReceived = new LongAdder();
        private final LongAdder bytesSent = new LongAdder();
        private final LongAdder packetsReceived = new LongAdder();
        private final LongAdder batchesSent = new LongAdder();
        private final LongAdder errors = new LongAdder();
        
        void recordConnection() { connections.increment(); }
        void recordDisconnection() { disconnections.increment(); }
        void recordBytesReceived(int bytes) { bytesReceived.add(bytes); }
        void recordBytesSent(int bytes) { bytesSent.add(bytes); }
        void recordPacketReceived() { packetsReceived.increment(); }
        void recordBatchSend(int packets) { batchesSent.increment(); }
        void recordError() { errors.increment(); }
        
        public long getActiveConnections() { 
            return connections.sum() - disconnections.sum(); 
        }
        
        public long getTotalConnections() { return connections.sum(); }
        public long getBytesReceived() { return bytesReceived.sum(); }
        public long getBytesSent() { return bytesSent.sum(); }
        public long getPacketsReceived() { return packetsReceived.sum(); }
        public long getBatchesSent() { return batchesSent.sum(); }
        public long getErrors() { return errors.sum(); }
        
        public double getThroughput() {
            return (getBytesReceived() + getBytesSent()) / 1024.0 / 1024.0; // MB
        }
        
        @Override
        public String toString() {
            return String.format("NetworkMetrics{active=%d, throughput=%.2f MB, packets=%d, batches=%d, errors=%d}",
                getActiveConnections(), getThroughput(), getPacketsReceived(), 
                getBatchesSent(), getErrors());
        }
    }
    
    public NetworkMetrics getMetrics() {
        return metrics;
    }
    
    public void shutdown() {
        try {
            if (serverChannel.isOpen()) {
                serverChannel.close();
            }
        } catch (IOException e) {
            LOGGER.warn("Error closing server channel", e);
        }
        
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        if (channelGroup != null && !channelGroup.isShutdown()) {
            channelGroup.shutdown();
        }
    }
}