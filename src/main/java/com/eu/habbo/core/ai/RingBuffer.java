package com.eu.habbo.core.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * High-performance ring buffer for time series data
 * Thread-safe circular buffer with fixed capacity
 */
public class RingBuffer<T> {
    
    private final Object[] buffer;
    private final int capacity;
    private volatile int head = 0;
    private volatile int tail = 0;
    private volatile int size = 0;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    
    public RingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive");
        }
        this.capacity = capacity;
        this.buffer = new Object[capacity];
    }
    
    /**
     * Add element to buffer (overwrites oldest if full)
     */
    public void add(T element) {
        lock.writeLock().lock();
        try {
            buffer[tail] = element;
            tail = (tail + 1) % capacity;
            
            if (size < capacity) {
                size++;
            } else {
                head = (head + 1) % capacity; // Overwrite oldest
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Get current size
     */
    public int size() {
        lock.readLock().lock();
        try {
            return size;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Check if buffer is empty
     */
    public boolean isEmpty() {
        return size() == 0;
    }
    
    /**
     * Check if buffer is full
     */
    public boolean isFull() {
        return size() == capacity;
    }
    
    /**
     * Get all data as list (oldest to newest)
     */
    @SuppressWarnings("unchecked")
    public List<T> getAllData() {
        lock.readLock().lock();
        try {
            List<T> result = new ArrayList<>(size);
            
            for (int i = 0; i < size; i++) {
                int index = (head + i) % capacity;
                result.add((T) buffer[index]);
            }
            
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get recent N data points (newest first)
     */
    @SuppressWarnings("unchecked")
    public List<T> getRecentData(int count) {
        lock.readLock().lock();
        try {
            int actualCount = Math.min(count, size);
            List<T> result = new ArrayList<>(actualCount);
            
            for (int i = 0; i < actualCount; i++) {
                int index = (tail - 1 - i + capacity) % capacity;
                result.add((T) buffer[index]);
            }
            
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get oldest data point
     */
    @SuppressWarnings("unchecked")
    public T getOldest() {
        lock.readLock().lock();
        try {
            return size > 0 ? (T) buffer[head] : null;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Get newest data point
     */
    @SuppressWarnings("unchecked")
    public T getNewest() {
        lock.readLock().lock();
        try {
            return size > 0 ? (T) buffer[(tail - 1 + capacity) % capacity] : null;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Clear all data
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            head = 0;
            tail = 0;
            size = 0;
            // Don't need to clear buffer array - overwriting is sufficient
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Get buffer utilization percentage
     */
    public double getUtilization() {
        return (double) size() / capacity;
    }
    
    @Override
    public String toString() {
        return String.format("RingBuffer{size=%d/%d, utilization=%.1f%%}", 
                           size(), capacity, getUtilization() * 100);
    }
}