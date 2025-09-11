package com.eu.habbo.util.collections;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance queue optimized for packet processing
 * Uses ring buffer architecture with MPSC (Multiple Producer Single Consumer) pattern
 */
public class HighPerformanceQueue<T> {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(HighPerformanceQueue.class);
    
    private final BlockingQueue<T> queue;
    private final int capacity;
    private final String queueName;
    
    // Performance metrics
    private final AtomicLong enqueueCount = new AtomicLong(0);
    private final AtomicLong dequeueCount = new AtomicLong(0);
    private final AtomicLong offerFailures = new AtomicLong(0);
    private final AtomicLong pollTimeouts = new AtomicLong(0);
    
    // Queue type enum
    public enum QueueType {
        ARRAY_BLOCKING_QUEUE,    // Bounded, array-based
        LINKED_BLOCKING_QUEUE,   // Bounded, linked-list based
        SYNCHRONOUS_QUEUE,       // No capacity, direct handoff
        PRIORITY_BLOCKING_QUEUE  // Priority-based ordering
    }
    
    public HighPerformanceQueue(String name, int capacity, QueueType type) {
        this.queueName = name;
        this.capacity = capacity;
        this.queue = createQueue(capacity, type);
        
        LOGGER.info("Created high-performance queue '{}' with capacity {} and type {}", 
                   name, capacity, type);
    }
    
    private BlockingQueue<T> createQueue(int capacity, QueueType type) {
        switch (type) {
            case ARRAY_BLOCKING_QUEUE:
                return new ArrayBlockingQueue<>(capacity, true); // Fair ordering
            case LINKED_BLOCKING_QUEUE:
                return capacity > 0 ? new LinkedBlockingQueue<>(capacity) : new LinkedBlockingQueue<>();
            case SYNCHRONOUS_QUEUE:
                return new SynchronousQueue<>(true); // Fair ordering
            case PRIORITY_BLOCKING_QUEUE:
                return new PriorityBlockingQueue<>(capacity);
            default:
                throw new IllegalArgumentException("Unknown queue type: " + type);
        }
    }
    
    /**
     * Add element to queue (blocking)
     */
    public void put(T element) throws InterruptedException {
        queue.put(element);
        enqueueCount.incrementAndGet();
    }
    
    /**
     * Add element to queue (non-blocking)
     */
    public boolean offer(T element) {
        boolean success = queue.offer(element);
        if (success) {
            enqueueCount.incrementAndGet();
        } else {
            offerFailures.incrementAndGet();
        }
        return success;
    }
    
    /**
     * Add element with timeout
     */
    public boolean offer(T element, long timeout, TimeUnit unit) throws InterruptedException {
        boolean success = queue.offer(element, timeout, unit);
        if (success) {
            enqueueCount.incrementAndGet();
        } else {
            offerFailures.incrementAndGet();
        }
        return success;
    }
    
    /**
     * Remove element from queue (blocking)
     */
    public T take() throws InterruptedException {
        T element = queue.take();
        dequeueCount.incrementAndGet();
        return element;
    }
    
    /**
     * Remove element from queue (non-blocking)
     */
    public T poll() {
        T element = queue.poll();
        if (element != null) {
            dequeueCount.incrementAndGet();
        }
        return element;
    }
    
    /**
     * Remove element with timeout
     */
    public T poll(long timeout, TimeUnit unit) throws InterruptedException {
        T element = queue.poll(timeout, unit);
        if (element != null) {
            dequeueCount.incrementAndGet();
        } else {
            pollTimeouts.incrementAndGet();
        }
        return element;
    }
    
    /**
     * Batch drain elements from queue
     */
    public int drainTo(java.util.Collection<? super T> collection, int maxElements) {
        int drained = queue.drainTo(collection, maxElements);
        dequeueCount.addAndGet(drained);
        return drained;
    }
    
    /**
     * Get current queue size
     */
    public int size() {
        return queue.size();
    }
    
    /**
     * Check if queue is empty
     */
    public boolean isEmpty() {
        return queue.isEmpty();
    }
    
    /**
     * Get remaining capacity
     */
    public int remainingCapacity() {
        return queue.remainingCapacity();
    }
    
    /**
     * Check if queue is near capacity
     */
    public boolean isNearCapacity(double threshold) {
        if (capacity == Integer.MAX_VALUE) return false;
        double usage = (double) size() / capacity;
        return usage >= threshold;
    }
    
    /**
     * Get queue utilization percentage
     */
    public double getUtilization() {
        if (capacity == Integer.MAX_VALUE) return 0.0;
        return (double) size() / capacity * 100.0;
    }
    
    /**
     * Get performance statistics
     */
    public QueueStats getStats() {
        return new QueueStats(
            queueName,
            size(),
            capacity,
            enqueueCount.get(),
            dequeueCount.get(),
            offerFailures.get(),
            pollTimeouts.get(),
            getUtilization()
        );
    }
    
    /**
     * Reset performance counters
     */
    public void resetStats() {
        enqueueCount.set(0);
        dequeueCount.set(0);
        offerFailures.set(0);
        pollTimeouts.set(0);
    }
    
    /**
     * Get performance report
     */
    public String getPerformanceReport() {
        QueueStats stats = getStats();
        return String.format(
            "[%s] Size: %d/%d (%.1f%%), Enqueued: %d, Dequeued: %d, Failures: %d, Timeouts: %d",
            stats.name, stats.currentSize, stats.capacity, stats.utilization,
            stats.enqueued, stats.dequeued, stats.offerFailures, stats.pollTimeouts
        );
    }
    
    /**
     * Queue statistics data class
     */
    public static class QueueStats {
        public final String name;
        public final int currentSize;
        public final int capacity;
        public final long enqueued;
        public final long dequeued;
        public final long offerFailures;
        public final long pollTimeouts;
        public final double utilization;
        
        public QueueStats(String name, int currentSize, int capacity, long enqueued,
                         long dequeued, long offerFailures, long pollTimeouts, double utilization) {
            this.name = name;
            this.currentSize = currentSize;
            this.capacity = capacity;
            this.enqueued = enqueued;
            this.dequeued = dequeued;
            this.offerFailures = offerFailures;
            this.pollTimeouts = pollTimeouts;
            this.utilization = utilization;
        }
        
        @Override
        public String toString() {
            return String.format(
                "QueueStats{name='%s', size=%d/%d, utilization=%.1f%%, enqueued=%d, dequeued=%d, failures=%d, timeouts=%d}",
                name, currentSize, capacity, utilization, enqueued, dequeued, offerFailures, pollTimeouts
            );
        }
    }
}