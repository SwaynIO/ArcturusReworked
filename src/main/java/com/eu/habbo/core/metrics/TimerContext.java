package com.eu.habbo.core.metrics;

/**
 * Context for timing operations - implements AutoCloseable for try-with-resources
 */
public class TimerContext implements AutoCloseable {
    
    public static final TimerContext NOOP = new TimerContext(null);
    
    private final TimerMetric timer;
    private final long startTime;
    private boolean closed = false;
    
    public TimerContext(TimerMetric timer) {
        this.timer = timer;
        this.startTime = System.nanoTime();
    }
    
    /**
     * Stop the timer and record the duration
     */
    public void stop() {
        if (!closed && timer != null) {
            long duration = System.nanoTime() - startTime;
            timer.recordTime(duration);
            closed = true;
        }
    }
    
    /**
     * Get elapsed time in nanoseconds (without stopping the timer)
     */
    public long getElapsedNanos() {
        return System.nanoTime() - startTime;
    }
    
    /**
     * Get elapsed time in milliseconds (without stopping the timer)
     */
    public double getElapsedMs() {
        return getElapsedNanos() / 1_000_000.0;
    }
    
    /**
     * Check if timer has been stopped
     */
    public boolean isClosed() {
        return closed;
    }
    
    @Override
    public void close() {
        stop();
    }
    
    @Override
    public String toString() {
        return String.format("TimerContext{elapsed=%.2fms, closed=%s}", 
                           getElapsedMs(), closed);
    }
}