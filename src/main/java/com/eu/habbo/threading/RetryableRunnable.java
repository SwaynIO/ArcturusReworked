package com.eu.habbo.threading;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runnable wrapper that implements automatic retry logic with exponential backoff
 * Useful for tasks that may fail due to temporary conditions (network issues, resource contention)
 */
public class RetryableRunnable implements Runnable {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(RetryableRunnable.class);
    
    private final Runnable wrapped;
    private final int maxRetries;
    private final ThreadPooling threadPool;
    private final AtomicInteger attemptCount;
    private final String taskName;
    
    // Configurable retry parameters
    private long baseDelayMs = 1000; // Start with 1 second
    private double backoffMultiplier = 2.0; // Double delay each time
    private long maxDelayMs = 30000; // Cap at 30 seconds
    
    public RetryableRunnable(Runnable wrapped, int maxRetries, ThreadPooling threadPool) {
        this(wrapped, maxRetries, threadPool, wrapped.getClass().getSimpleName());
    }
    
    public RetryableRunnable(Runnable wrapped, int maxRetries, ThreadPooling threadPool, String taskName) {
        if (wrapped == null) {
            throw new IllegalArgumentException("Wrapped runnable cannot be null");
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("Max retries cannot be negative");
        }
        if (threadPool == null) {
            throw new IllegalArgumentException("Thread pool cannot be null");
        }
        
        this.wrapped = wrapped;
        this.maxRetries = maxRetries;
        this.threadPool = threadPool;
        this.taskName = taskName != null ? taskName : "RetryableTask";
        this.attemptCount = new AtomicInteger(0);
    }
    
    /**
     * Configure retry parameters
     */
    public RetryableRunnable withRetryConfig(long baseDelayMs, double backoffMultiplier, long maxDelayMs) {
        this.baseDelayMs = baseDelayMs;
        this.backoffMultiplier = backoffMultiplier;
        this.maxDelayMs = maxDelayMs;
        return this;
    }
    
    @Override
    public void run() {
        int currentAttempt = attemptCount.incrementAndGet();
        
        try {
            wrapped.run();
            
            // Success - log if this wasn't the first attempt
            if (currentAttempt > 1) {
                LOGGER.info("Task '{}' succeeded on attempt {}/{}", 
                           taskName, currentAttempt, maxRetries + 1);
            }
            
        } catch (Exception e) {
            handleFailure(e, currentAttempt);
        }
    }
    
    private void handleFailure(Exception e, int currentAttempt) {
        if (currentAttempt <= maxRetries) {
            // Calculate delay for next attempt using exponential backoff
            long delay = calculateDelay(currentAttempt - 1);
            
            LOGGER.warn("Task '{}' failed on attempt {}/{}, retrying in {}ms: {}", 
                       taskName, currentAttempt, maxRetries + 1, delay, e.getMessage());
            
            // Schedule retry
            threadPool.run(this, delay);
            
        } else {
            // All retries exhausted
            LOGGER.error("Task '{}' failed after {} attempts, giving up: {}", 
                        taskName, currentAttempt, e.getMessage(), e);
            
            // Optionally call a failure callback here
            onAllRetriesExhausted(e);
        }
    }
    
    private long calculateDelay(int retryNumber) {
        double delay = baseDelayMs * Math.pow(backoffMultiplier, retryNumber);
        
        // Add some jitter to avoid thundering herd
        double jitter = 0.1 * delay * (Math.random() - 0.5);
        delay += jitter;
        
        return Math.min((long) delay, maxDelayMs);
    }
    
    /**
     * Called when all retries are exhausted - can be overridden by subclasses
     */
    protected void onAllRetriesExhausted(Exception lastException) {
        // Default: do nothing
        // Subclasses can override this for custom failure handling
    }
    
    /**
     * Get current attempt count
     */
    public int getCurrentAttempt() {
        return attemptCount.get();
    }
    
    /**
     * Check if task has failed all retries
     */
    public boolean hasExhaustedRetries() {
        return attemptCount.get() > maxRetries;
    }
    
    /**
     * Reset attempt count (useful for reusing the same RetryableRunnable)
     */
    public void resetAttempts() {
        attemptCount.set(0);
    }
    
    @Override
    public String toString() {
        return "RetryableRunnable{" +
                "taskName='" + taskName + '\'' +
                ", maxRetries=" + maxRetries +
                ", currentAttempt=" + attemptCount.get() +
                ", wrapped=" + wrapped.getClass().getSimpleName() +
                '}';
    }
}