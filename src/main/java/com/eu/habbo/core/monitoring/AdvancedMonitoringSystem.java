package com.eu.habbo.core.monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.*;

/**
 * Advanced monitoring system with real-time analytics and alerting
 * Provides comprehensive system health monitoring with intelligent alerting
 */
public class AdvancedMonitoringSystem {
    private static final Logger LOGGER = LoggerFactory.getLogger(AdvancedMonitoringSystem.class);
    
    private static AdvancedMonitoringSystem instance;
    
    private final MetricsCollector metricsCollector;
    private final AlertingEngine alertingEngine;
    private final HealthChecker healthChecker;
    private final PerformanceDashboard dashboard;
    private final ScheduledExecutorService scheduler;
    
    // Monitoring intervals (in seconds)
    private static final int METRICS_INTERVAL = 10;
    private static final int HEALTH_CHECK_INTERVAL = 30;
    private static final int DASHBOARD_UPDATE_INTERVAL = 5;
    
    private AdvancedMonitoringSystem() {
        this.metricsCollector = new MetricsCollector();
        this.alertingEngine = new AlertingEngine();
        this.healthChecker = new HealthChecker();
        this.dashboard = new PerformanceDashboard();
        
        this.scheduler = Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "AdvancedMonitoring");
            t.setDaemon(true);
            return t;
        });
        
        LOGGER.info("Advanced Monitoring System initialized");
    }
    
    public static synchronized AdvancedMonitoringSystem getInstance() {
        if (instance == null) {
            instance = new AdvancedMonitoringSystem();
        }
        return instance;
    }
    
    public void start() {
        // Start metrics collection
        scheduler.scheduleWithFixedDelay(metricsCollector::collectMetrics, 
            0, METRICS_INTERVAL, TimeUnit.SECONDS);
        
        // Start health checking
        scheduler.scheduleWithFixedDelay(healthChecker::performHealthCheck, 
            30, HEALTH_CHECK_INTERVAL, TimeUnit.SECONDS);
        
        // Start dashboard updates
        scheduler.scheduleWithFixedDelay(dashboard::updateDashboard, 
            10, DASHBOARD_UPDATE_INTERVAL, TimeUnit.SECONDS);
        
        LOGGER.info("Advanced Monitoring System started");
    }
    
    /**
     * Comprehensive metrics collection system
     */
    private class MetricsCollector {
        private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
        private final RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        private final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        private final List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        
        private final Map<String, SystemMetrics> metricsHistory = new ConcurrentHashMap<>();
        private final AtomicLong lastCollectionTime = new AtomicLong(System.currentTimeMillis());
        
        void collectMetrics() {
            try {
                SystemMetrics metrics = gatherCurrentMetrics();
                
                // Store in history (keep last 1000 entries)
                String timestamp = String.valueOf(System.currentTimeMillis());
                metricsHistory.put(timestamp, metrics);
                
                // Clean old entries
                if (metricsHistory.size() > 1000) {
                    String oldestKey = Collections.min(metricsHistory.keySet());
                    metricsHistory.remove(oldestKey);
                }
                
                // Check for alerts
                alertingEngine.checkMetrics(metrics);
                
                lastCollectionTime.set(System.currentTimeMillis());
                
            } catch (Exception e) {
                LOGGER.error("Metrics collection failed", e);
            }
        }
        
        private SystemMetrics gatherCurrentMetrics() {
            // Memory metrics
            MemoryUsage heapMemory = memoryBean.getHeapMemoryUsage();
            MemoryUsage nonHeapMemory = memoryBean.getNonHeapMemoryUsage();
            
            // Thread metrics
            int threadCount = threadBean.getThreadCount();
            int daemonThreadCount = threadBean.getDaemonThreadCount();
            int peakThreadCount = threadBean.getPeakThreadCount();
            
            // GC metrics
            long totalGcTime = 0;
            long totalGcCollections = 0;
            for (GarbageCollectorMXBean gcBean : gcBeans) {
                totalGcTime += gcBean.getCollectionTime();
                totalGcCollections += gcBean.getCollectionCount();
            }
            
            // CPU metrics
            double cpuUsage = -1;
            if (osBean instanceof com.sun.management.OperatingSystemMXBean) {
                cpuUsage = ((com.sun.management.OperatingSystemMXBean) osBean).getProcessCpuLoad() * 100;
            }
            
            // System load
            double systemLoad = osBean.getSystemLoadAverage();
            
            // Runtime metrics
            long uptime = runtimeBean.getUptime();
            
            return new SystemMetrics(
                System.currentTimeMillis(),
                heapMemory.getUsed(),
                heapMemory.getMax(),
                nonHeapMemory.getUsed(),
                threadCount,
                daemonThreadCount,
                peakThreadCount,
                totalGcTime,
                totalGcCollections,
                cpuUsage,
                systemLoad,
                uptime
            );
        }
        
        public SystemMetrics getCurrentMetrics() {
            return gatherCurrentMetrics();
        }
        
        public List<SystemMetrics> getMetricsHistory(int minutes) {
            long cutoffTime = System.currentTimeMillis() - (minutes * 60 * 1000);
            return metricsHistory.values().stream()
                .filter(metrics -> metrics.timestamp >= cutoffTime)
                .sorted((a, b) -> Long.compare(a.timestamp, b.timestamp))
                .collect(ArrayList::new, (list, item) -> list.add(item), ArrayList::addAll);
        }
    }
    
    /**
     * Intelligent alerting system with configurable thresholds
     */
    private class AlertingEngine {
        private final Map<AlertType, AlertRule> alertRules = new EnumMap<>(AlertType.class);
        private final Set<AlertType> activeAlerts = EnumSet.noneOf(AlertType.class);
        private final LongAdder totalAlerts = new LongAdder();
        
        AlertingEngine() {
            // Configure default alert rules
            alertRules.put(AlertType.HIGH_MEMORY, new AlertRule(0.85, 0.75, 30));
            alertRules.put(AlertType.HIGH_CPU, new AlertRule(80.0, 60.0, 60));
            alertRules.put(AlertType.HIGH_THREAD_COUNT, new AlertRule(500, 300, 60));
            alertRules.put(AlertType.FREQUENT_GC, new AlertRule(10, 5, 120));
            alertRules.put(AlertType.HIGH_SYSTEM_LOAD, new AlertRule(8.0, 4.0, 60));
        }
        
        void checkMetrics(SystemMetrics metrics) {
            // Check memory usage
            double memoryUsage = (double) metrics.heapUsed / metrics.heapMax;
            checkAlert(AlertType.HIGH_MEMORY, memoryUsage, metrics.timestamp);
            
            // Check CPU usage
            if (metrics.cpuUsage >= 0) {
                checkAlert(AlertType.HIGH_CPU, metrics.cpuUsage, metrics.timestamp);
            }
            
            // Check thread count
            checkAlert(AlertType.HIGH_THREAD_COUNT, metrics.threadCount, metrics.timestamp);
            
            // Check GC frequency (collections per minute)
            double gcFrequency = calculateGCFrequency(metrics);
            if (gcFrequency > 0) {
                checkAlert(AlertType.FREQUENT_GC, gcFrequency, metrics.timestamp);
            }
            
            // Check system load
            if (metrics.systemLoad > 0) {
                checkAlert(AlertType.HIGH_SYSTEM_LOAD, metrics.systemLoad, metrics.timestamp);
            }
        }
        
        private void checkAlert(AlertType alertType, double value, long timestamp) {
            AlertRule rule = alertRules.get(alertType);
            if (rule == null) return;
            
            if (value >= rule.criticalThreshold) {
                if (!activeAlerts.contains(alertType) || 
                    (timestamp - rule.lastAlertTime) >= (rule.cooldownSeconds * 1000)) {
                    
                    triggerAlert(alertType, value, AlertLevel.CRITICAL);
                    rule.lastAlertTime = timestamp;
                }
            } else if (value >= rule.warningThreshold) {
                if (!activeAlerts.contains(alertType) || 
                    (timestamp - rule.lastAlertTime) >= (rule.cooldownSeconds * 1000)) {
                    
                    triggerAlert(alertType, value, AlertLevel.WARNING);
                    rule.lastAlertTime = timestamp;
                }
            } else {
                // Clear alert if value is below warning threshold
                if (activeAlerts.contains(alertType)) {
                    clearAlert(alertType);
                }
            }
        }
        
        private double calculateGCFrequency(SystemMetrics metrics) {
            // Simple GC frequency calculation - would be enhanced with historical data
            return metrics.gcCollections / (metrics.uptime / 60000.0);
        }
        
        private void triggerAlert(AlertType alertType, double value, AlertLevel level) {
            activeAlerts.add(alertType);
            totalAlerts.increment();
            
            String message = String.format("%s Alert: %s = %.2f", 
                level, alertType.getDescription(), value);
            
            if (level == AlertLevel.CRITICAL) {
                LOGGER.error("CRITICAL ALERT: {}", message);
            } else {
                LOGGER.warn("WARNING ALERT: {}", message);
            }
            
            // Here you could add additional alert handlers (email, webhook, etc.)
        }
        
        private void clearAlert(AlertType alertType) {
            if (activeAlerts.remove(alertType)) {
                LOGGER.info("Alert cleared: {}", alertType.getDescription());
            }
        }
        
        public Set<AlertType> getActiveAlerts() {
            return EnumSet.copyOf(activeAlerts);
        }
        
        public long getTotalAlertsCount() {
            return totalAlerts.sum();
        }
    }
    
    /**
     * System health checker with intelligent diagnostics
     */
    private class HealthChecker {
        private final Map<String, HealthCheck> healthChecks = new ConcurrentHashMap<>();
        
        HealthChecker() {
            // Register default health checks
            registerHealthCheck("memory", this::checkMemoryHealth);
            registerHealthCheck("threads", this::checkThreadHealth);
            registerHealthCheck("gc", this::checkGCHealth);
            registerHealthCheck("cpu", this::checkCPUHealth);
        }
        
        void registerHealthCheck(String name, HealthCheck check) {
            healthChecks.put(name, check);
        }
        
        void performHealthCheck() {
            SystemMetrics metrics = metricsCollector.getCurrentMetrics();
            
            for (Map.Entry<String, HealthCheck> entry : healthChecks.entrySet()) {
                try {
                    HealthStatus status = entry.getValue().check(metrics);
                    if (status.level != HealthLevel.HEALTHY) {
                        LOGGER.warn("Health check '{}': {} - {}", 
                                  entry.getKey(), status.level, status.message);
                    }
                } catch (Exception e) {
                    LOGGER.error("Health check '{}' failed", entry.getKey(), e);
                }
            }
        }
        
        private HealthStatus checkMemoryHealth(SystemMetrics metrics) {
            double usage = (double) metrics.heapUsed / metrics.heapMax;
            
            if (usage > 0.9) {
                return new HealthStatus(HealthLevel.CRITICAL, 
                    String.format("Memory usage critical: %.1f%%", usage * 100));
            } else if (usage > 0.8) {
                return new HealthStatus(HealthLevel.WARNING, 
                    String.format("Memory usage high: %.1f%%", usage * 100));
            }
            
            return new HealthStatus(HealthLevel.HEALTHY, "Memory usage normal");
        }
        
        private HealthStatus checkThreadHealth(SystemMetrics metrics) {
            if (metrics.threadCount > 1000) {
                return new HealthStatus(HealthLevel.CRITICAL, 
                    String.format("Thread count critical: %d", metrics.threadCount));
            } else if (metrics.threadCount > 500) {
                return new HealthStatus(HealthLevel.WARNING, 
                    String.format("Thread count high: %d", metrics.threadCount));
            }
            
            return new HealthStatus(HealthLevel.HEALTHY, "Thread count normal");
        }
        
        private HealthStatus checkGCHealth(SystemMetrics metrics) {
            double gcFrequency = metrics.gcCollections / (metrics.uptime / 60000.0);
            
            if (gcFrequency > 20) {
                return new HealthStatus(HealthLevel.WARNING, 
                    String.format("GC frequency high: %.1f/min", gcFrequency));
            }
            
            return new HealthStatus(HealthLevel.HEALTHY, "GC frequency normal");
        }
        
        private HealthStatus checkCPUHealth(SystemMetrics metrics) {
            if (metrics.cpuUsage > 90) {
                return new HealthStatus(HealthLevel.CRITICAL, 
                    String.format("CPU usage critical: %.1f%%", metrics.cpuUsage));
            } else if (metrics.cpuUsage > 70) {
                return new HealthStatus(HealthLevel.WARNING, 
                    String.format("CPU usage high: %.1f%%", metrics.cpuUsage));
            }
            
            return new HealthStatus(HealthLevel.HEALTHY, "CPU usage normal");
        }
    }
    
    /**
     * Real-time performance dashboard
     */
    private class PerformanceDashboard {
        private volatile String lastDashboard = "";
        private final AtomicLong lastUpdate = new AtomicLong(0);
        
        void updateDashboard() {
            SystemMetrics metrics = metricsCollector.getCurrentMetrics();
            Set<AlertType> alerts = alertingEngine.getActiveAlerts();
            
            StringBuilder dashboard = new StringBuilder();
            dashboard.append("\n=== ADVANCED MONITORING DASHBOARD ===\n");
            
            // System overview
            dashboard.append(String.format("Uptime: %s\n", formatUptime(metrics.uptime)));
            dashboard.append(String.format("Memory: %.1f%% (%.1f MB / %.1f MB)\n",
                (double) metrics.heapUsed / metrics.heapMax * 100,
                metrics.heapUsed / 1024.0 / 1024.0,
                metrics.heapMax / 1024.0 / 1024.0));
            
            if (metrics.cpuUsage >= 0) {
                dashboard.append(String.format("CPU: %.1f%%\n", metrics.cpuUsage));
            }
            
            dashboard.append(String.format("Threads: %d (Peak: %d)\n", 
                metrics.threadCount, metrics.peakThreadCount));
            
            if (metrics.systemLoad > 0) {
                dashboard.append(String.format("System Load: %.2f\n", metrics.systemLoad));
            }
            
            dashboard.append(String.format("GC: %d collections (%.1fs total)\n",
                metrics.gcCollections, metrics.gcTime / 1000.0));
            
            // Active alerts
            if (!alerts.isEmpty()) {
                dashboard.append("\n🚨 ACTIVE ALERTS:\n");
                for (AlertType alert : alerts) {
                    dashboard.append(String.format("- %s\n", alert.getDescription()));
                }
            } else {
                dashboard.append("\n✅ No active alerts\n");
            }
            
            dashboard.append("=====================================");
            
            lastDashboard = dashboard.toString();
            lastUpdate.set(System.currentTimeMillis());
        }
        
        private String formatUptime(long uptimeMs) {
            long seconds = uptimeMs / 1000;
            long minutes = seconds / 60;
            long hours = minutes / 60;
            long days = hours / 24;
            
            if (days > 0) {
                return String.format("%dd %dh %dm", days, hours % 24, minutes % 60);
            } else if (hours > 0) {
                return String.format("%dh %dm", hours, minutes % 60);
            } else {
                return String.format("%dm %ds", minutes, seconds % 60);
            }
        }
        
        public String getDashboard() {
            return lastDashboard;
        }
        
        public void printDashboard() {
            LOGGER.info(lastDashboard);
        }
    }
    
    // Data classes and interfaces
    @FunctionalInterface
    private interface HealthCheck {
        HealthStatus check(SystemMetrics metrics);
    }
    
    public static class SystemMetrics {
        public final long timestamp;
        public final long heapUsed;
        public final long heapMax;
        public final long nonHeapUsed;
        public final int threadCount;
        public final int daemonThreadCount;
        public final int peakThreadCount;
        public final long gcTime;
        public final long gcCollections;
        public final double cpuUsage;
        public final double systemLoad;
        public final long uptime;
        
        public SystemMetrics(long timestamp, long heapUsed, long heapMax, long nonHeapUsed,
                           int threadCount, int daemonThreadCount, int peakThreadCount,
                           long gcTime, long gcCollections, double cpuUsage, 
                           double systemLoad, long uptime) {
            this.timestamp = timestamp;
            this.heapUsed = heapUsed;
            this.heapMax = heapMax;
            this.nonHeapUsed = nonHeapUsed;
            this.threadCount = threadCount;
            this.daemonThreadCount = daemonThreadCount;
            this.peakThreadCount = peakThreadCount;
            this.gcTime = gcTime;
            this.gcCollections = gcCollections;
            this.cpuUsage = cpuUsage;
            this.systemLoad = systemLoad;
            this.uptime = uptime;
        }
    }
    
    private static class AlertRule {
        final double criticalThreshold;
        final double warningThreshold;
        final int cooldownSeconds;
        long lastAlertTime = 0;
        
        AlertRule(double criticalThreshold, double warningThreshold, int cooldownSeconds) {
            this.criticalThreshold = criticalThreshold;
            this.warningThreshold = warningThreshold;
            this.cooldownSeconds = cooldownSeconds;
        }
    }
    
    private static class HealthStatus {
        final HealthLevel level;
        final String message;
        
        HealthStatus(HealthLevel level, String message) {
            this.level = level;
            this.message = message;
        }
    }
    
    public enum AlertType {
        HIGH_MEMORY("High Memory Usage"),
        HIGH_CPU("High CPU Usage"),
        HIGH_THREAD_COUNT("High Thread Count"),
        FREQUENT_GC("Frequent Garbage Collection"),
        HIGH_SYSTEM_LOAD("High System Load");
        
        private final String description;
        
        AlertType(String description) {
            this.description = description;
        }
        
        public String getDescription() {
            return description;
        }
    }
    
    public enum AlertLevel {
        WARNING, CRITICAL
    }
    
    public enum HealthLevel {
        HEALTHY, WARNING, CRITICAL
    }
    
    // Public API methods
    public SystemMetrics getCurrentMetrics() {
        return metricsCollector.getCurrentMetrics();
    }
    
    public List<SystemMetrics> getMetricsHistory(int minutes) {
        return metricsCollector.getMetricsHistory(minutes);
    }
    
    public Set<AlertType> getActiveAlerts() {
        return alertingEngine.getActiveAlerts();
    }
    
    public String getDashboard() {
        return dashboard.getDashboard();
    }
    
    public void printDashboard() {
        dashboard.printDashboard();
    }
    
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        LOGGER.info("Advanced Monitoring System shutdown completed");
    }
}