package com.eu.habbo.core.profiling;

import com.eu.habbo.core.metrics.RealTimeMetrics;
import com.eu.habbo.util.collections.OptimizedConcurrentMap;
import com.eu.habbo.core.ai.RingBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Real-time performance profiler for Arcturus Morningstar Reworked
 * Provides comprehensive runtime performance analysis with minimal overhead
 */
public class RealTimeProfiler {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(RealTimeProfiler.class);
    
    // Singleton instance for global profiling
    private static final RealTimeProfiler INSTANCE = new RealTimeProfiler();
    
    // Profiling components
    private final MethodProfiler methodProfiler;
    private final MemoryProfiler memoryProfiler;
    private final ThreadProfiler threadProfiler;
    private final HotSpotDetector hotSpotDetector;
    
    // Profiling control
    private volatile boolean profilingEnabled = true;
    private volatile int samplingIntervalMs = 100;
    private final ScheduledExecutorService scheduler;
    private final RealTimeMetrics metrics;
    
    // Profile data storage
    private final OptimizedConcurrentMap<String, ProfileEntry> profileData;
    private final RingBuffer<SystemSnapshot> systemHistory;
    
    // Performance impact tracking
    private final AtomicLong profilingOverheadNs = new AtomicLong(0);
    private final LongAdder totalSamples = new LongAdder();
    
    private RealTimeProfiler() {
        this.profileData = new OptimizedConcurrentMap<>("ProfileData", 2048);
        this.systemHistory = new RingBuffer<>(1440); // 24 hours of minute snapshots
        this.metrics = RealTimeMetrics.getInstance();
        
        this.methodProfiler = new MethodProfiler();
        this.memoryProfiler = new MemoryProfiler();
        this.threadProfiler = new ThreadProfiler();
        this.hotSpotDetector = new HotSpotDetector();
        
        this.scheduler = Executors.newScheduledThreadPool(3, r -> {
            Thread t = new Thread(r, "RealTimeProfiler-" + r.hashCode());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY + 1); // Low priority to minimize impact
            return t;
        });
        
        startProfiling();
    }
    
    public static RealTimeProfiler getInstance() {
        return INSTANCE;
    }
    
    /**
     * Profile method execution with automatic timing
     */
    public ProfiledExecution profileMethod(String methodName, Runnable method) {
        if (!profilingEnabled) {
            method.run();
            return new ProfiledExecution(methodName, 0, true, "Profiling disabled");
        }
        
        long startTime = System.nanoTime();
        boolean success = false;
        String errorMessage = null;
        
        try {
            method.run();
            success = true;
        } catch (Exception e) {
            errorMessage = e.getMessage();
            throw e;
        } finally {
            long executionTime = System.nanoTime() - startTime;
            recordMethodExecution(methodName, executionTime, success);
        }
        
        return new ProfiledExecution(methodName, System.nanoTime() - startTime, success, errorMessage);
    }
    
    /**
     * Profile method execution with return value
     */
    public <T> ProfiledResult<T> profileMethod(String methodName, Callable<T> method) {
        if (!profilingEnabled) {
            try {
                return new ProfiledResult<>(method.call(), methodName, 0, true, null);
            } catch (Exception e) {
                return new ProfiledResult<>(null, methodName, 0, false, e.getMessage());
            }
        }
        
        long startTime = System.nanoTime();
        T result = null;
        boolean success = false;
        String errorMessage = null;
        
        try {
            result = method.call();
            success = true;
        } catch (Exception e) {
            errorMessage = e.getMessage();
        } finally {
            long executionTime = System.nanoTime() - startTime;
            recordMethodExecution(methodName, executionTime, success);
        }
        
        return new ProfiledResult<>(result, methodName, System.nanoTime() - startTime, success, errorMessage);
    }
    
    /**
     * Get comprehensive performance report
     */
    public PerformanceReport generateReport() {
        long reportStartTime = System.nanoTime();
        
        try {
            // Collect data from all profiling components
            MethodProfilerReport methodReport = methodProfiler.generateReport();
            MemoryProfilerReport memoryReport = memoryProfiler.generateReport();
            ThreadProfilerReport threadReport = threadProfiler.generateReport();
            HotSpotReport hotSpotReport = hotSpotDetector.generateReport();
            
            // System overview
            SystemSnapshot currentSnapshot = captureSystemSnapshot();
            
            return new PerformanceReport(
                methodReport, memoryReport, threadReport, hotSpotReport,
                currentSnapshot, getProfilingOverhead(), totalSamples.sum()
            );
            
        } finally {
            // Track profiling overhead
            long overhead = System.nanoTime() - reportStartTime;
            profilingOverheadNs.addAndGet(overhead);
        }
    }
    
    /**
     * Get top performance bottlenecks
     */
    public List<PerformanceBottleneck> getTopBottlenecks(int count) {
        List<PerformanceBottleneck> bottlenecks = new ArrayList<>();
        
        // Analyze method performance
        List<ProfileEntry> slowMethods = profileData.values().stream()
            .sorted((a, b) -> Double.compare(b.getAverageExecutionTimeNs(), a.getAverageExecutionTimeNs()))
            .limit(count)
            .collect(Collectors.toList());
        
        for (ProfileEntry entry : slowMethods) {
            bottlenecks.add(new PerformanceBottleneck(
                BottleneckType.SLOW_METHOD,
                entry.getMethodName(),
                String.format("Average execution time: %.2fms", entry.getAverageExecutionTimeNs() / 1_000_000.0),
                entry.getExecutionCount(),
                entry.getAverageExecutionTimeNs() / 1_000_000.0 // Convert to milliseconds
            ));
        }
        
        // Add memory bottlenecks
        if (memoryProfiler.isMemoryPressureHigh()) {
            bottlenecks.add(new PerformanceBottleneck(
                BottleneckType.MEMORY_PRESSURE,
                "System Memory",
                "High memory utilization detected",
                1,
                memoryProfiler.getCurrentMemoryUtilization() * 100
            ));
        }
        
        // Add thread contention bottlenecks
        Map<String, Long> contentionHotSpots = threadProfiler.getContentionHotSpots();
        for (Map.Entry<String, Long> entry : contentionHotSpots.entrySet()) {
            if (entry.getValue() > 1000000) { // More than 1ms total contention
                bottlenecks.add(new PerformanceBottleneck(
                    BottleneckType.THREAD_CONTENTION,
                    entry.getKey(),
                    "High thread contention detected",
                    1,
                    entry.getValue() / 1_000_000.0
                ));
            }
        }
        
        return bottlenecks.stream()
                .sorted((a, b) -> Double.compare(b.impact, a.impact))
                .limit(count)
                .collect(Collectors.toList());
    }
    
    /**
     * Configure profiler settings
     */
    public void configure(boolean enabled, int samplingIntervalMs) {
        this.profilingEnabled = enabled;
        this.samplingIntervalMs = Math.max(10, Math.min(10000, samplingIntervalMs));
        
        LOGGER.info("Real-time profiler configured - enabled: {}, interval: {}ms", 
                   enabled, this.samplingIntervalMs);
    }
    
    /**
     * Clear all profiling data
     */
    public void clearProfileData() {
        profileData.clear();
        systemHistory.clear();
        methodProfiler.clear();
        memoryProfiler.clear();
        threadProfiler.clear();
        hotSpotDetector.clear();
        profilingOverheadNs.set(0);
        totalSamples.reset();
        
        LOGGER.info("All profiling data cleared");
    }
    
    // Private helper methods
    
    private void startProfiling() {
        // System profiling every sampling interval
        scheduler.scheduleAtFixedRate(() -> {
            if (profilingEnabled) {
                try {
                    performSystemProfiling();
                } catch (Exception e) {
                    LOGGER.error("Error in system profiling", e);
                }
            }
        }, samplingIntervalMs, samplingIntervalMs, TimeUnit.MILLISECONDS);
        
        // Memory profiling every 5 seconds
        scheduler.scheduleAtFixedRate(() -> {
            if (profilingEnabled) {
                try {
                    memoryProfiler.profileMemory();
                } catch (Exception e) {
                    LOGGER.error("Error in memory profiling", e);
                }
            }
        }, 5000, 5000, TimeUnit.MILLISECONDS);
        
        // Generate performance report every 10 minutes
        scheduler.scheduleAtFixedRate(() -> {
            try {
                generatePeriodicReport();
            } catch (Exception e) {
                LOGGER.error("Error generating periodic performance report", e);
            }
        }, 600000, 600000, TimeUnit.MILLISECONDS);
        
        LOGGER.info("Real-time profiler started with {}ms sampling interval", samplingIntervalMs);
    }
    
    private void performSystemProfiling() {
        long profileStartTime = System.nanoTime();
        
        try {
            // Capture system snapshot
            SystemSnapshot snapshot = captureSystemSnapshot();
            systemHistory.add(snapshot);
            
            // Profile threads
            threadProfiler.profileThreads();
            
            // Update hot spot detection
            hotSpotDetector.updateHotSpots(profileData.values());
            
            totalSamples.increment();
            
        } finally {
            long overhead = System.nanoTime() - profileStartTime;
            profilingOverheadNs.addAndGet(overhead);
        }
    }
    
    private SystemSnapshot captureSystemSnapshot() {
        Runtime runtime = Runtime.getRuntime();
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        
        return new SystemSnapshot(
            System.currentTimeMillis(),
            runtime.totalMemory() - runtime.freeMemory(), // Used memory
            runtime.totalMemory(),
            runtime.maxMemory(),
            threadBean.getThreadCount(),
            threadBean.getDaemonThreadCount(),
            getCurrentCpuUsage(),
            getSystemLoadAverage()
        );
    }
    
    private double getCurrentCpuUsage() {
        // Simplified CPU usage - in production would use more sophisticated monitoring
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunOsBean = (com.sun.management.OperatingSystemMXBean) osBean;
            return sunOsBean.getProcessCpuLoad();
        }
        return osBean.getSystemLoadAverage();
    }
    
    private double getSystemLoadAverage() {
        return ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
    }
    
    private void recordMethodExecution(String methodName, long executionTimeNs, boolean success) {
        ProfileEntry entry = profileData.computeIfAbsent(methodName, k -> new ProfileEntry(methodName));
        entry.recordExecution(executionTimeNs, success);
        
        // Update metrics
        metrics.recordTime("profiler.method." + methodName, executionTimeNs);
        metrics.incrementCounter("profiler.method.calls");
        
        if (!success) {
            metrics.incrementCounter("profiler.method.errors");
        }
    }
    
    private double getProfilingOverhead() {
        long totalSampleCount = totalSamples.sum();
        if (totalSampleCount == 0) return 0.0;
        
        return (profilingOverheadNs.get() / 1_000_000.0) / totalSampleCount; // Average overhead in ms
    }
    
    private void generatePeriodicReport() {
        try {
            PerformanceReport report = generateReport();
            
            StringBuilder summary = new StringBuilder();
            summary.append("\n=== Real-Time Profiler Report ===\n");
            summary.append(String.format("Total Samples: %,d\n", report.totalSamples));
            summary.append(String.format("Profiling Overhead: %.3fms/sample\n", report.profilingOverheadMs));
            summary.append(String.format("Memory Usage: %.1f%% (%d MB / %d MB)\n",
                report.currentSnapshot.memoryUtilization * 100,
                report.currentSnapshot.usedMemory / 1024 / 1024,
                report.currentSnapshot.maxMemory / 1024 / 1024));
            summary.append(String.format("Active Threads: %d (%d daemon)\n",
                report.currentSnapshot.threadCount, report.currentSnapshot.daemonThreadCount));
            summary.append(String.format("CPU Usage: %.1f%%\n", report.currentSnapshot.cpuUsage * 100));
            
            // Top bottlenecks
            List<PerformanceBottleneck> bottlenecks = getTopBottlenecks(5);
            if (!bottlenecks.isEmpty()) {
                summary.append("\nTop Performance Bottlenecks:\n");
                for (int i = 0; i < bottlenecks.size(); i++) {
                    PerformanceBottleneck bottleneck = bottlenecks.get(i);
                    summary.append(String.format("  %d. %s: %s (Impact: %.2f)\n", 
                        i + 1, bottleneck.source, bottleneck.description, bottleneck.impact));
                }
            }
            
            summary.append("==============================\n");
            LOGGER.info(summary.toString());
            
        } catch (Exception e) {
            LOGGER.error("Error in periodic report generation", e);
        }
    }
    
    /**
     * Shutdown the profiler
     */
    public void shutdown() {
        profilingEnabled = false;
        scheduler.shutdown();
        
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        LOGGER.info("Real-time profiler shut down");
    }
    
    // Data classes
    
    public static class ProfiledExecution {
        public final String methodName;
        public final long executionTimeNs;
        public final boolean success;
        public final String errorMessage;
        
        public ProfiledExecution(String methodName, long executionTimeNs, boolean success, String errorMessage) {
            this.methodName = methodName;
            this.executionTimeNs = executionTimeNs;
            this.success = success;
            this.errorMessage = errorMessage;
        }
        
        public double getExecutionTimeMs() {
            return executionTimeNs / 1_000_000.0;
        }
    }
    
    public static class ProfiledResult<T> extends ProfiledExecution {
        public final T result;
        
        public ProfiledResult(T result, String methodName, long executionTimeNs, boolean success, String errorMessage) {
            super(methodName, executionTimeNs, success, errorMessage);
            this.result = result;
        }
    }
    
    public static class PerformanceBottleneck {
        public final BottleneckType type;
        public final String source;
        public final String description;
        public final long frequency;
        public final double impact;
        
        public PerformanceBottleneck(BottleneckType type, String source, String description, 
                                   long frequency, double impact) {
            this.type = type;
            this.source = source;
            this.description = description;
            this.frequency = frequency;
            this.impact = impact;
        }
        
        @Override
        public String toString() {
            return String.format("PerformanceBottleneck{type=%s, source='%s', impact=%.2f}", 
                               type, source, impact);
        }
    }
    
    public static class SystemSnapshot {
        public final long timestamp;
        public final long usedMemory;
        public final long totalMemory;
        public final long maxMemory;
        public final int threadCount;
        public final int daemonThreadCount;
        public final double cpuUsage;
        public final double systemLoad;
        public final double memoryUtilization;
        
        public SystemSnapshot(long timestamp, long usedMemory, long totalMemory, long maxMemory,
                            int threadCount, int daemonThreadCount, double cpuUsage, double systemLoad) {
            this.timestamp = timestamp;
            this.usedMemory = usedMemory;
            this.totalMemory = totalMemory;
            this.maxMemory = maxMemory;
            this.threadCount = threadCount;
            this.daemonThreadCount = daemonThreadCount;
            this.cpuUsage = cpuUsage;
            this.systemLoad = systemLoad;
            this.memoryUtilization = maxMemory > 0 ? (double) usedMemory / maxMemory : 0.0;
        }
    }
    
    public static class PerformanceReport {
        public final MethodProfilerReport methodReport;
        public final MemoryProfilerReport memoryReport;
        public final ThreadProfilerReport threadReport;
        public final HotSpotReport hotSpotReport;
        public final SystemSnapshot currentSnapshot;
        public final double profilingOverheadMs;
        public final long totalSamples;
        
        public PerformanceReport(MethodProfilerReport methodReport, MemoryProfilerReport memoryReport,
                               ThreadProfilerReport threadReport, HotSpotReport hotSpotReport,
                               SystemSnapshot currentSnapshot, double profilingOverheadMs, long totalSamples) {
            this.methodReport = methodReport;
            this.memoryReport = memoryReport;
            this.threadReport = threadReport;
            this.hotSpotReport = hotSpotReport;
            this.currentSnapshot = currentSnapshot;
            this.profilingOverheadMs = profilingOverheadMs;
            this.totalSamples = totalSamples;
        }
    }
    
    public enum BottleneckType {
        SLOW_METHOD, MEMORY_PRESSURE, THREAD_CONTENTION, HIGH_GC, IO_WAIT
    }
}