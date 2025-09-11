package com.eu.habbo.threading;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;

public class HabboExecutorService extends ScheduledThreadPoolExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger(HabboExecutorService.class);

    public HabboExecutorService(int corePoolSize, ThreadFactory threadFactory) {
        super(corePoolSize, threadFactory);
        
        // Performance optimizations
        setMaximumPoolSize(corePoolSize * 2); // Allow expansion under load
        setKeepAliveTime(60, java.util.concurrent.TimeUnit.SECONDS);
        allowCoreThreadTimeOut(true); // Allow core threads to timeout
        
        // Use FIFO policy for consistent performance
        setRejectedExecutionHandler(new RejectedExecutionHandlerImpl());
    }

    @Override
    protected void afterExecute(Runnable r, Throwable t) {
        super.afterExecute(r, t);

        if (t != null && !(t instanceof IOException)) {
            LOGGER.error("Error in HabboExecutorService", t);
        }
    }
}
