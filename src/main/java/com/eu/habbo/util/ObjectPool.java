package com.eu.habbo.util;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;

/**
 * High-performance object pool for frequently used objects
 * Reduces garbage collection pressure by reusing objects
 */
public class ObjectPool<T> {
    
    private final ConcurrentLinkedQueue<T> objects;
    private final Supplier<T> objectFactory;
    private final int maxSize;
    
    public ObjectPool(Supplier<T> objectFactory, int maxSize) {
        this.objectFactory = objectFactory;
        this.maxSize = maxSize;
        this.objects = new ConcurrentLinkedQueue<>();
        
        // Pre-populate with half the max size
        for (int i = 0; i < maxSize / 2; i++) {
            objects.offer(objectFactory.get());
        }
    }
    
    /**
     * Borrow an object from the pool
     */
    public T borrow() {
        T object = objects.poll();
        return object != null ? object : objectFactory.get();
    }
    
    /**
     * Return an object to the pool
     */
    public void returnObject(T object) {
        if (object != null && objects.size() < maxSize) {
            objects.offer(object);
        }
    }
    
    /**
     * Get current pool size
     */
    public int size() {
        return objects.size();
    }
    
    /**
     * Clear the pool
     */
    public void clear() {
        objects.clear();
    }
}