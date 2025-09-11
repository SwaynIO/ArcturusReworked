package com.eu.habbo.messages;

import com.eu.habbo.util.ObjectPool;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * High-performance message batch processor for Arcturus Morningstar Reworked
 * Processes multiple messages in batches to improve throughput and reduce latency
 */
public class MessageBatchProcessor {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(MessageBatchProcessor.class);
    
    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final int DEFAULT_TIMEOUT_MS = 10;
    
    // Object pool for reusing batch lists
    private static final ObjectPool<List<ServerMessage>> BATCH_POOL = 
        new ObjectPool<>(() -> new ArrayList<>(DEFAULT_BATCH_SIZE), 16);
    
    private final BlockingQueue<ServerMessage> messageQueue;
    private final int batchSize;
    private final int timeoutMs;
    private final AtomicBoolean running;
    private Thread processorThread;
    
    public MessageBatchProcessor() {
        this(DEFAULT_BATCH_SIZE, DEFAULT_TIMEOUT_MS);
    }
    
    public MessageBatchProcessor(int batchSize, int timeoutMs) {
        this.messageQueue = new LinkedBlockingQueue<>(batchSize * 10);
        this.batchSize = batchSize;
        this.timeoutMs = timeoutMs;
        this.running = new AtomicBoolean(false);
    }
    
    /**
     * Start the batch processor
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            processorThread = new Thread(this::processMessages, "Message-Batch-Processor");
            processorThread.setDaemon(true);
            processorThread.start();
            LOGGER.info("Message batch processor started with batch size: {} and timeout: {}ms", 
                       batchSize, timeoutMs);
        }
    }
    
    /**
     * Stop the batch processor
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            if (processorThread != null) {
                processorThread.interrupt();
            }
            LOGGER.info("Message batch processor stopped");
        }
    }
    
    /**
     * Queue a message for batch processing
     */
    public boolean queueMessage(ServerMessage message) {
        if (running.get() && message != null) {
            return messageQueue.offer(message);
        }
        return false;
    }
    
    /**
     * Get current queue size
     */
    public int getQueueSize() {
        return messageQueue.size();
    }
    
    /**
     * Process messages in batches
     */
    private void processMessages() {
        List<ServerMessage> batch = BATCH_POOL.borrow();
        
        try {
            while (running.get()) {
                try {
                    // Wait for at least one message
                    ServerMessage firstMessage = messageQueue.poll(timeoutMs, 
                        java.util.concurrent.TimeUnit.MILLISECONDS);
                    
                    if (firstMessage != null) {
                        batch.clear();
                        batch.add(firstMessage);
                        
                        // Collect additional messages up to batch size
                        drainQueue(batch);
                        
                        // Process the batch
                        processBatch(batch);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    LOGGER.error("Error processing message batch", e);
                }
            }
        } finally {
            BATCH_POOL.returnObject(batch);
        }
    }
    
    /**
     * Drain additional messages from queue up to batch size
     */
    private void drainQueue(List<ServerMessage> batch) {
        int remaining = batchSize - batch.size();
        if (remaining > 0) {
            List<ServerMessage> additional = new ArrayList<>(remaining);
            int drained = messageQueue.drainTo(additional, remaining);
            if (drained > 0) {
                batch.addAll(additional);
            }
        }
    }
    
    /**
     * Process a batch of messages
     */
    private void processBatch(List<ServerMessage> batch) {
        if (batch.isEmpty()) return;
        
        try {
            // Group messages by client/room for efficient processing
            // This would integrate with the existing message handling system
            for (ServerMessage message : batch) {
                // Process individual message
                processMessage(message);
            }
        } catch (Exception e) {
            LOGGER.error("Error processing message batch of size: {}", batch.size(), e);
        }
    }
    
    /**
     * Process individual message - would integrate with existing system
     */
    private void processMessage(ServerMessage message) {
        // Implementation would integrate with existing message processing
        // This is a placeholder for the actual message handling logic
    }
}