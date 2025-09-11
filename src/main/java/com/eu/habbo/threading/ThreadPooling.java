package com.eu.habbo.threading;

import com.eu.habbo.Emulator;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class ThreadPooling {

    private static final Logger LOGGER = LoggerFactory.getLogger(ThreadPooling.class);

    public final int threads;
    private final ScheduledExecutorService scheduledPool;
    private volatile boolean canAdd;

    public ThreadPooling(Integer threads) {
        // Optimize thread count based on available processors if not specified
        int optimalThreads = threads != null ? threads : Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        this.threads = optimalThreads;
        
        // Use optimized thread factory with proper naming and daemon threads
        DefaultThreadFactory threadFactory = new DefaultThreadFactory("Habbo-Pool");
        this.scheduledPool = new HabboExecutorService(this.threads, threadFactory);
        
        // Configure executor for better performance
        if (this.scheduledPool instanceof HabboExecutorService) {
            HabboExecutorService executor = (HabboExecutorService) this.scheduledPool;
            executor.setKeepAliveTime(30, TimeUnit.SECONDS);
            executor.allowCoreThreadTimeOut(true);
            executor.setRejectedExecutionHandler(new RejectedExecutionHandlerImpl());
        }
        
        this.canAdd = true;
        LOGGER.info("Thread Pool -> Loaded with {} threads!", this.threads);
    }

    public ScheduledFuture run(Runnable run) {
        return this.run(run, 0);
    }
    
    public ScheduledFuture run(Runnable run, long delay) {
        if (run == null) {
            LOGGER.warn("Attempted to schedule null runnable");
            return null;
        }
        
        try {
            if (this.canAdd) {
                return this.scheduledPool.schedule(new SafeRunnable(run), delay, TimeUnit.MILLISECONDS);
            } else {
                if (Emulator.isShuttingDown) {
                    // Execute immediately on current thread during shutdown
                    new SafeRunnable(run).run();
                }
                LOGGER.debug("Cannot schedule task - pool is not accepting new tasks");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to schedule task with delay {}ms", delay, e);
        }

        return null;
    }
    
    /**
     * Run task with retry capability
     */
    public ScheduledFuture runWithRetry(Runnable run, long delay, int maxRetries) {
        return this.run(new RetryableRunnable(run, maxRetries, this), delay);
    }
    
    /**
     * Schedule recurring task with better error handling
     */
    public ScheduledFuture runRepeating(Runnable run, long initialDelay, long period) {
        try {
            if (this.canAdd) {
                return this.scheduledPool.scheduleAtFixedRate(
                    new SafeRunnable(run), 
                    initialDelay, 
                    period, 
                    TimeUnit.MILLISECONDS
                );
            }
        } catch (Exception e) {
            LOGGER.error("Failed to schedule repeating task with period {}ms", period, e);
        }
        return null;
    }
    
    /**
     * Get current pool statistics
     */
    public String getPoolStats() {
        if (scheduledPool instanceof HabboExecutorService) {
            HabboExecutorService executor = (HabboExecutorService) scheduledPool;
            return String.format(
                "Pool Stats - Threads: %d, Active: %d, Queue: %d, Completed: %d",
                threads,
                executor.getActiveCount(),
                executor.getQueue().size(),
                executor.getCompletedTaskCount()
            );
        }
        return "Pool Stats - Basic info not available";
    }

    public void shutDown() {
        this.canAdd = false;
        this.scheduledPool.shutdownNow();

        LOGGER.info("Threading -> Disposed!");
    }

    public void setCanAdd(boolean canAdd) {
        this.canAdd = canAdd;
    }

    public ScheduledExecutorService getService() {
        return this.scheduledPool;
    }


}
