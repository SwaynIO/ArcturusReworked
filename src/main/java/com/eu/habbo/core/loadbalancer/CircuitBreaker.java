package com.eu.habbo.core.loadbalancer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Circuit breaker pattern implementation for protecting against failing services
 * Automatically opens circuit when failure rate exceeds threshold
 */
public class CircuitBreaker {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(CircuitBreaker.class);
    
    private final String name;
    private volatile State state = State.CLOSED;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private final AtomicLong lastSuccessTime = new AtomicLong(0);
    
    // Configuration parameters
    private final int failureThreshold;
    private final long timeoutMs;
    private final int minimumCalls;
    private final double failureRateThreshold;
    
    // Default configuration
    private static final int DEFAULT_FAILURE_THRESHOLD = 5;
    private static final long DEFAULT_TIMEOUT_MS = 60000; // 1 minute
    private static final int DEFAULT_MINIMUM_CALLS = 10;
    private static final double DEFAULT_FAILURE_RATE = 0.5; // 50%
    
    public CircuitBreaker(String name) {
        this(name, DEFAULT_FAILURE_THRESHOLD, DEFAULT_TIMEOUT_MS, 
             DEFAULT_MINIMUM_CALLS, DEFAULT_FAILURE_RATE);
    }
    
    public CircuitBreaker(String name, int failureThreshold, long timeoutMs,
                         int minimumCalls, double failureRateThreshold) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.timeoutMs = timeoutMs;
        this.minimumCalls = minimumCalls;
        this.failureRateThreshold = failureRateThreshold;
    }
    
    /**
     * Check if the circuit allows execution
     */
    public boolean canExecute() {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                return attemptReset();
            case HALF_OPEN:
                return true; // Allow limited execution to test if service recovered
            default:
                return false;
        }
    }
    
    /**
     * Record a successful execution
     */
    public void recordSuccess() {
        successCount.incrementAndGet();
        lastSuccessTime.set(System.currentTimeMillis());
        
        if (state == State.HALF_OPEN) {
            // Service seems to be recovered
            reset();
        }
    }
    
    /**
     * Record a failed execution
     */
    public void recordFailure() {
        failureCount.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());
        
        if (shouldTrip()) {
            trip();
        }
    }
    
    /**
     * Check if circuit should trip to OPEN state
     */
    private boolean shouldTrip() {
        int totalCalls = failureCount.get() + successCount.get();
        
        if (totalCalls < minimumCalls) {
            return false; // Not enough data
        }
        
        // Check if consecutive failures exceed threshold
        if (failureCount.get() >= failureThreshold) {
            return true;
        }
        
        // Check if failure rate exceeds threshold
        double failureRate = (double) failureCount.get() / totalCalls;
        return failureRate >= failureRateThreshold;
    }
    
    /**
     * Trip the circuit to OPEN state
     */
    private synchronized void trip() {
        if (state != State.OPEN) {
            state = State.OPEN;
            LOGGER.warn("Circuit breaker '{}' tripped to OPEN state after {} failures", 
                       name, failureCount.get());
        }
    }
    
    /**
     * Attempt to reset circuit from OPEN to HALF_OPEN
     */
    private synchronized boolean attemptReset() {
        long timeSinceLastFailure = System.currentTimeMillis() - lastFailureTime.get();
        
        if (timeSinceLastFailure >= timeoutMs) {
            state = State.HALF_OPEN;
            LOGGER.info("Circuit breaker '{}' transitioning to HALF_OPEN state for testing", name);
            return true;
        }
        
        return false;
    }
    
    /**
     * Reset circuit to CLOSED state
     */
    private synchronized void reset() {
        state = State.CLOSED;
        failureCount.set(0);
        successCount.set(0);
        LOGGER.info("Circuit breaker '{}' reset to CLOSED state", name);
    }
    
    /**
     * Force reset the circuit breaker
     */
    public synchronized void forceReset() {
        reset();
        LOGGER.info("Circuit breaker '{}' forcibly reset", name);
    }
    
    /**
     * Get current circuit breaker statistics
     */
    public CircuitBreakerStats getStats() {
        int totalCalls = failureCount.get() + successCount.get();
        double failureRate = totalCalls > 0 ? (double) failureCount.get() / totalCalls : 0.0;
        
        return new CircuitBreakerStats(
            name,
            state,
            failureCount.get(),
            successCount.get(),
            failureRate,
            System.currentTimeMillis() - lastFailureTime.get(),
            System.currentTimeMillis() - lastSuccessTime.get()
        );
    }
    
    /**
     * Get current state
     */
    public State getState() {
        return state;
    }
    
    /**
     * Get circuit breaker name
     */
    public String getName() {
        return name;
    }
    
    @Override
    public String toString() {
        return String.format("CircuitBreaker{name='%s', state=%s, failures=%d, successes=%d}",
                           name, state, failureCount.get(), successCount.get());
    }
    
    /**
     * Circuit breaker states
     */
    public enum State {
        CLOSED,    // Normal operation - calls are allowed
        OPEN,      // Circuit is open - calls are blocked
        HALF_OPEN  // Testing if service recovered - limited calls allowed
    }
    
    /**
     * Circuit breaker statistics
     */
    public static class CircuitBreakerStats {
        public final String name;
        public final State state;
        public final int failures;
        public final int successes;
        public final double failureRate;
        public final long timeSinceLastFailure;
        public final long timeSinceLastSuccess;
        
        public CircuitBreakerStats(String name, State state, int failures, int successes,
                                 double failureRate, long timeSinceLastFailure, long timeSinceLastSuccess) {
            this.name = name;
            this.state = state;
            this.failures = failures;
            this.successes = successes;
            this.failureRate = failureRate;
            this.timeSinceLastFailure = timeSinceLastFailure;
            this.timeSinceLastSuccess = timeSinceLastSuccess;
        }
        
        @Override
        public String toString() {
            return String.format("CircuitBreakerStats{name='%s', state=%s, failures=%d, successes=%d, failureRate=%.2f%%}",
                               name, state, failures, successes, failureRate * 100);
        }
    }
}