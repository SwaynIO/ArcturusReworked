package com.eu.habbo.threading;

import com.eu.habbo.util.PerformanceUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Safe wrapper for Runnable tasks with enhanced error handling and performance monitoring
 * Prevents task failures from affecting other tasks in the thread pool
 */
public class SafeRunnable implements Runnable {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(SafeRunnable.class);
    
    private final Runnable wrapped;
    private final String taskName;
    private final long createdAt;
    
    public SafeRunnable(Runnable wrapped) {
        this(wrapped, wrapped.getClass().getSimpleName());
    }
    
    public SafeRunnable(Runnable wrapped, String taskName) {
        if (wrapped == null) {
            throw new IllegalArgumentException("Wrapped runnable cannot be null");
        }
        this.wrapped = wrapped;
        this.taskName = taskName != null ? taskName : "UnknownTask";
        this.createdAt = System.currentTimeMillis();
    }
    
    @Override
    public void run() {
        long startTime = System.nanoTime();
        String threadName = Thread.currentThread().getName();
        
        try {
            // Check for tasks that have been waiting too long (potential leak detection)
            long waitTime = System.currentTimeMillis() - createdAt;
            if (waitTime > 30000) { // 30 seconds
                LOGGER.warn("Task '{}' waited {}ms before execution on thread '{}'", 
                           taskName, waitTime, threadName);
            }
            
            // Execute the wrapped task
            wrapped.run();
            
        } catch (InterruptedException e) {
            // Restore interrupted status
            Thread.currentThread().interrupt();
            LOGGER.debug("Task '{}' was interrupted on thread '{}'", taskName, threadName);
        } catch (Exception e) {
            // Log error but don't let it propagate to prevent thread pool corruption
            LOGGER.error("Uncaught exception in task '{}' on thread '{}': {}", 
                        taskName, threadName, e.getMessage(), e);
        } catch (Error e) {
            // Log critical errors (OutOfMemoryError, etc.)
            LOGGER.error("Critical error in task '{}' on thread '{}': {}", 
                        taskName, threadName, e.getMessage(), e);
            // Re-throw Error as it indicates serious JVM issues
            throw e;
        } finally {
            // Record performance metrics
            long executionTime = System.nanoTime() - startTime;
            PerformanceUtils.endTiming("task." + taskName, startTime);
            
            // Log slow tasks
            long executionMs = executionTime / 1_000_000;
            if (executionMs > 1000) { // Tasks taking more than 1 second
                LOGGER.warn("Slow task '{}' took {}ms on thread '{}'", 
                           taskName, executionMs, threadName);
            }
        }
    }
    
    /**
     * Get the wrapped runnable
     */
    public Runnable getWrapped() {
        return wrapped;
    }
    
    /**
     * Get the task name
     */
    public String getTaskName() {
        return taskName;
    }
    
    @Override
    public String toString() {
        return "SafeRunnable{" +
                "taskName='" + taskName + '\'' +
                ", wrapped=" + wrapped.getClass().getSimpleName() +
                ", createdAt=" + createdAt +
                '}';
    }
}