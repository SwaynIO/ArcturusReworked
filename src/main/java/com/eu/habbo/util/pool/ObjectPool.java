package com.eu.habbo.util.pool;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * High-performance object pool for Arcturus Morningstar Reworked
 * Reduces GC pressure by reusing frequently allocated objects like
 * ServerMessage, RoomTile copies, and other temporary objects.
 * 
 * Thread-safe implementation using BlockingQueue for concurrent access.
 */
public class ObjectPool<T> {
    
    private final BlockingQueue<T> pool;
    private final Supplier<T> objectFactory;
    private final int maxSize;
    private final AtomicInteger createdObjects = new AtomicInteger(0);
    
    /**
     * Create object pool with factory and max size
     * @param objectFactory Function to create new objects when pool is empty
     * @param maxSize Maximum objects to keep in pool (prevents memory leaks)
     */
    public ObjectPool(Supplier<T> objectFactory, int maxSize) {
        this.objectFactory = objectFactory;
        this.maxSize = maxSize;
        this.pool = new LinkedBlockingQueue<>(maxSize);
    }
    
    /**
     * Get object from pool or create new one if pool is empty
     * @return Reused or new object instance
     */
    public T acquire() {
        T object = pool.poll();
        if (object == null) {
            object = objectFactory.get();
            createdObjects.incrementAndGet();
        }
        return object;
    }
    
    /**
     * Return object to pool for reuse
     * Object will be ignored if pool is full (prevents memory leaks)
     * @param object Object to return to pool
     */
    public void release(T object) {
        if (object != null) {
            // Only add to pool if there's space (prevent memory leaks)
            pool.offer(object); // Non-blocking, returns false if full
        }
    }
    
    /**
     * Get current pool size
     */
    public int getPoolSize() {
        return pool.size();
    }
    
    /**
     * Get total objects created by this pool
     */
    public int getTotalCreated() {
        return createdObjects.get();
    }
    
    /**
     * Get pool utilization percentage
     */
    public double getUtilization() {
        return (double) pool.size() / maxSize * 100.0;
    }
    
    /**
     * Clear all objects from pool
     */
    public void clear() {
        pool.clear();
    }
}