package com.eu.habbo.core.loadbalancer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Multi-tier intelligent load balancer with adaptive routing and predictive scaling
 * Supports multiple tiers (Edge, Application, Database) with intelligent request routing
 */
public class MultiTierLoadBalancer {
    private static final Logger LOGGER = LoggerFactory.getLogger(MultiTierLoadBalancer.class);
    
    private static MultiTierLoadBalancer instance;
    
    private final Map<LoadBalancerTier, TierManager> tierManagers;
    private final RequestRouter requestRouter;
    private final PredictiveScaler predictiveScaler;
    private final HealthMonitor healthMonitor;
    private final LoadBalancerMetrics metrics;
    private final ScheduledExecutorService scheduler;
    
    // Configuration
    private static final int HEALTH_CHECK_INTERVAL = 15; // seconds
    private static final int SCALING_CHECK_INTERVAL = 60; // seconds
    private static final double SCALE_UP_THRESHOLD = 0.8;
    private static final double SCALE_DOWN_THRESHOLD = 0.3;
    
    private MultiTierLoadBalancer() {
        this.tierManagers = new EnumMap<>(LoadBalancerTier.class);
        this.requestRouter = new RequestRouter();
        this.predictiveScaler = new PredictiveScaler();
        this.healthMonitor = new HealthMonitor();
        this.metrics = new LoadBalancerMetrics();
        
        this.scheduler = Executors.newScheduledThreadPool(3, r -> {
            Thread t = new Thread(r, "MultiTierLB");
            t.setDaemon(true);
            return t;
        });
        
        // Initialize tier managers
        initializeTierManagers();
        
        LOGGER.info("Multi-Tier Load Balancer initialized with {} tiers", tierManagers.size());
    }
    
    public static synchronized MultiTierLoadBalancer getInstance() {
        if (instance == null) {
            instance = new MultiTierLoadBalancer();
        }
        return instance;
    }
    
    private void initializeTierManagers() {
        tierManagers.put(LoadBalancerTier.EDGE, new TierManager(LoadBalancerTier.EDGE));
        tierManagers.put(LoadBalancerTier.APPLICATION, new TierManager(LoadBalancerTier.APPLICATION));
        tierManagers.put(LoadBalancerTier.DATABASE, new TierManager(LoadBalancerTier.DATABASE));
        tierManagers.put(LoadBalancerTier.CACHE, new TierManager(LoadBalancerTier.CACHE));
    }
    
    public void start() {
        // Start health monitoring
        scheduler.scheduleWithFixedDelay(healthMonitor::performHealthChecks,
            HEALTH_CHECK_INTERVAL, HEALTH_CHECK_INTERVAL, TimeUnit.SECONDS);
        
        // Start predictive scaling
        scheduler.scheduleWithFixedDelay(predictiveScaler::evaluateScaling,
            SCALING_CHECK_INTERVAL, SCALING_CHECK_INTERVAL, TimeUnit.SECONDS);
        
        // Start metrics collection
        scheduler.scheduleWithFixedDelay(this::collectMetrics,
            30, 30, TimeUnit.SECONDS);
        
        LOGGER.info("Multi-Tier Load Balancer started");
    }
    
    /**
     * Route request through the multi-tier architecture
     */
    public LoadBalancingResult routeRequest(LoadBalancingRequest request) {
        long startTime = System.nanoTime();
        
        try {
            // Route through tiers in order
            ServerNode edgeNode = routeToTier(LoadBalancerTier.EDGE, request);
            if (edgeNode == null) {
                return createFailureResult(request, "No edge servers available");
            }
            
            ServerNode appNode = routeToTier(LoadBalancerTier.APPLICATION, request);
            if (appNode == null) {
                return createFailureResult(request, "No application servers available");
            }
            
            // Database/Cache routing is conditional
            ServerNode dataNode = null;
            if (request.requiresDatabase()) {
                dataNode = routeToTier(LoadBalancerTier.DATABASE, request);
            } else if (request.requiresCache()) {
                dataNode = routeToTier(LoadBalancerTier.CACHE, request);
            }
            
            long routingTime = System.nanoTime() - startTime;
            
            LoadBalancingResult result = new LoadBalancingResult(
                request.getId(), true, edgeNode, appNode, dataNode, 
                routingTime, "Success"
            );
            
            metrics.recordSuccessfulRouting(routingTime);
            return result;
            
        } catch (Exception e) {
            LOGGER.error("Request routing failed for: {}", request.getId(), e);
            metrics.recordFailedRouting();
            return createFailureResult(request, e.getMessage());
        }
    }
    
    private ServerNode routeToTier(LoadBalancerTier tier, LoadBalancingRequest request) {
        TierManager manager = tierManagers.get(tier);
        return manager != null ? manager.selectServer(request) : null;
    }
    
    private LoadBalancingResult createFailureResult(LoadBalancingRequest request, String error) {
        return new LoadBalancingResult(
            request.getId(), false, null, null, null, 0, error
        );
    }
    
    /**
     * Add server to specific tier
     */
    public void addServer(LoadBalancerTier tier, ServerNode server) {
        TierManager manager = tierManagers.get(tier);
        if (manager != null) {
            manager.addServer(server);
            LOGGER.info("Added server {} to tier {}", server.getId(), tier);
        }
    }
    
    /**
     * Remove server from specific tier
     */
    public void removeServer(LoadBalancerTier tier, String serverId) {
        TierManager manager = tierManagers.get(tier);
        if (manager != null) {
            manager.removeServer(serverId);
            LOGGER.info("Removed server {} from tier {}", serverId, tier);
        }
    }
    
    /**
     * Tier-specific server management
     */
    private class TierManager {
        private final LoadBalancerTier tier;
        private final ConcurrentHashMap<String, ServerNode> servers = new ConcurrentHashMap<>();
        private final AtomicInteger roundRobinIndex = new AtomicInteger(0);
        private final LoadBalancingStrategy strategy;
        
        TierManager(LoadBalancerTier tier) {
            this.tier = tier;
            this.strategy = selectOptimalStrategy(tier);
        }
        
        private LoadBalancingStrategy selectOptimalStrategy(LoadBalancerTier tier) {
            switch (tier) {
                case EDGE:
                    return LoadBalancingStrategy.GEOGRAPHIC_PROXIMITY;
                case APPLICATION:
                    return LoadBalancingStrategy.LEAST_CONNECTIONS;
                case DATABASE:
                    return LoadBalancingStrategy.CONSISTENT_HASH;
                case CACHE:
                    return LoadBalancingStrategy.WEIGHTED_ROUND_ROBIN;
                default:
                    return LoadBalancingStrategy.ROUND_ROBIN;
            }
        }
        
        ServerNode selectServer(LoadBalancingRequest request) {
            List<ServerNode> availableServers = getHealthyServers();
            if (availableServers.isEmpty()) {
                return null;
            }
            
            switch (strategy) {
                case ROUND_ROBIN:
                    return selectRoundRobin(availableServers);
                case WEIGHTED_ROUND_ROBIN:
                    return selectWeightedRoundRobin(availableServers);
                case LEAST_CONNECTIONS:
                    return selectLeastConnections(availableServers);
                case LEAST_RESPONSE_TIME:
                    return selectLeastResponseTime(availableServers);
                case CONSISTENT_HASH:
                    return selectConsistentHash(availableServers, request);
                case GEOGRAPHIC_PROXIMITY:
                    return selectGeographicProximity(availableServers, request);
                default:
                    return selectRoundRobin(availableServers);
            }
        }
        
        private ServerNode selectRoundRobin(List<ServerNode> servers) {
            int index = Math.abs(roundRobinIndex.getAndIncrement()) % servers.size();
            return servers.get(index);
        }
        
        private ServerNode selectWeightedRoundRobin(List<ServerNode> servers) {
            int totalWeight = servers.stream().mapToInt(ServerNode::getWeight).sum();
            int randomWeight = ThreadLocalRandom.current().nextInt(totalWeight);
            
            int currentWeight = 0;
            for (ServerNode server : servers) {
                currentWeight += server.getWeight();
                if (randomWeight < currentWeight) {
                    return server;
                }
            }
            
            return servers.get(0);
        }
        
        private ServerNode selectLeastConnections(List<ServerNode> servers) {
            return servers.stream()
                .min(Comparator.comparingInt(ServerNode::getActiveConnections))
                .orElse(servers.get(0));
        }
        
        private ServerNode selectLeastResponseTime(List<ServerNode> servers) {
            return servers.stream()
                .min(Comparator.comparingLong(ServerNode::getAverageResponseTime))
                .orElse(servers.get(0));
        }
        
        private ServerNode selectConsistentHash(List<ServerNode> servers, LoadBalancingRequest request) {
            // Simple consistent hashing based on request key
            int hash = Math.abs(request.getRoutingKey().hashCode());
            int index = hash % servers.size();
            return servers.get(index);
        }
        
        private ServerNode selectGeographicProximity(List<ServerNode> servers, LoadBalancingRequest request) {
            // Placeholder for geographic routing - would use actual location data
            String clientRegion = request.getClientRegion();
            
            ServerNode regionMatch = servers.stream()
                .filter(server -> clientRegion.equals(server.getRegion()))
                .findFirst()
                .orElse(null);
            
            return regionMatch != null ? regionMatch : selectRoundRobin(servers);
        }
        
        private List<ServerNode> getHealthyServers() {
            return servers.values().stream()
                .filter(server -> server.getHealthStatus() == HealthStatus.HEALTHY)
                .collect(Collectors.toList());
        }
        
        void addServer(ServerNode server) {
            servers.put(server.getId(), server);
        }
        
        void removeServer(String serverId) {
            servers.remove(serverId);
        }
        
        public int getServerCount() {
            return servers.size();
        }
        
        public int getHealthyServerCount() {
            return getHealthyServers().size();
        }
    }
    
    /**
     * Intelligent request router with caching and optimization
     */
    private class RequestRouter {
        private final ConcurrentHashMap<String, RouteCache> routeCache = new ConcurrentHashMap<>();
        private final AtomicLong cacheHits = new AtomicLong();
        private final AtomicLong cacheMisses = new AtomicLong();
        
        String determineOptimalRoute(LoadBalancingRequest request) {
            String cacheKey = generateRouteCacheKey(request);
            RouteCache cached = routeCache.get(cacheKey);
            
            if (cached != null && !cached.isExpired()) {
                cacheHits.incrementAndGet();
                return cached.route;
            }
            
            cacheMisses.incrementAndGet();
            
            // Calculate optimal route
            String optimalRoute = calculateOptimalRoute(request);
            
            // Cache the route
            routeCache.put(cacheKey, new RouteCache(optimalRoute, System.currentTimeMillis() + 300000)); // 5 min TTL
            
            return optimalRoute;
        }
        
        private String calculateOptimalRoute(LoadBalancingRequest request) {
            // Complex routing logic considering multiple factors
            StringBuilder route = new StringBuilder();
            
            // Consider request type, client location, current load, etc.
            route.append(request.getType()).append("|");
            route.append(request.getClientRegion()).append("|");
            route.append(System.currentTimeMillis() / 60000); // minute-based routing
            
            return route.toString();
        }
        
        private String generateRouteCacheKey(LoadBalancingRequest request) {
            return String.format("%s_%s_%s", 
                request.getType(), request.getClientRegion(), request.getRoutingKey());
        }
        
        public double getCacheHitRate() {
            long hits = cacheHits.get();
            long total = hits + cacheMisses.get();
            return total > 0 ? (double) hits / total : 0.0;
        }
    }
    
    /**
     * Predictive scaling based on historical patterns and current load
     */
    private class PredictiveScaler {
        private final Map<LoadBalancerTier, ScalingHistory> scalingHistory = new EnumMap<>(LoadBalancerTier.class);
        
        PredictiveScaler() {
            for (LoadBalancerTier tier : LoadBalancerTier.values()) {
                scalingHistory.put(tier, new ScalingHistory());
            }
        }
        
        void evaluateScaling() {
            for (Map.Entry<LoadBalancerTier, TierManager> entry : tierManagers.entrySet()) {
                LoadBalancerTier tier = entry.getKey();
                TierManager manager = entry.getValue();
                
                evaluateTierScaling(tier, manager);
            }
        }
        
        private void evaluateTierScaling(LoadBalancerTier tier, TierManager manager) {
            ScalingHistory history = scalingHistory.get(tier);
            
            // Calculate current load metrics
            double currentLoad = calculateTierLoad(tier);
            history.recordLoad(currentLoad);
            
            // Predict future load
            double predictedLoad = predictFutureLoad(history);
            
            // Make scaling decisions
            if (predictedLoad > SCALE_UP_THRESHOLD && manager.getHealthyServerCount() > 0) {
                scaleUp(tier, manager);
            } else if (predictedLoad < SCALE_DOWN_THRESHOLD && manager.getServerCount() > 1) {
                scaleDown(tier, manager);
            }
        }
        
        private double calculateTierLoad(LoadBalancerTier tier) {
            TierManager manager = tierManagers.get(tier);
            if (manager == null || manager.getHealthyServerCount() == 0) {
                return 0.0;
            }
            
            // Simplified load calculation - would be more sophisticated in reality
            return ThreadLocalRandom.current().nextDouble(0.2, 0.9);
        }
        
        private double predictFutureLoad(ScalingHistory history) {
            List<Double> recentLoads = history.getRecentLoads(10);
            if (recentLoads.isEmpty()) {
                return 0.5; // Default moderate load
            }
            
            // Simple moving average prediction
            double sum = recentLoads.stream().mapToDouble(Double::doubleValue).sum();
            double average = sum / recentLoads.size();
            
            // Add trend factor
            if (recentLoads.size() >= 2) {
                double trend = recentLoads.get(recentLoads.size() - 1) - recentLoads.get(0);
                average += trend * 0.1; // 10% trend influence
            }
            
            return Math.max(0.0, Math.min(1.0, average));
        }
        
        private void scaleUp(LoadBalancerTier tier, TierManager manager) {
            LOGGER.info("Scaling up tier: {}", tier);
            // In real implementation, would trigger auto-scaling
            metrics.recordScaleUp(tier);
        }
        
        private void scaleDown(LoadBalancerTier tier, TierManager manager) {
            LOGGER.info("Scaling down tier: {}", tier);
            // In real implementation, would trigger auto-scaling
            metrics.recordScaleDown(tier);
        }
    }
    
    /**
     * Comprehensive health monitoring for all tiers
     */
    private class HealthMonitor {
        void performHealthChecks() {
            for (Map.Entry<LoadBalancerTier, TierManager> entry : tierManagers.entrySet()) {
                LoadBalancerTier tier = entry.getKey();
                TierManager manager = entry.getValue();
                
                checkTierHealth(tier, manager);
            }
        }
        
        private void checkTierHealth(LoadBalancerTier tier, TierManager manager) {
            for (ServerNode server : manager.servers.values()) {
                HealthStatus newStatus = performHealthCheck(server);
                
                if (newStatus != server.getHealthStatus()) {
                    server.setHealthStatus(newStatus);
                    
                    if (newStatus == HealthStatus.UNHEALTHY) {
                        LOGGER.warn("Server {} in tier {} marked unhealthy", server.getId(), tier);
                        metrics.recordUnhealthyServer(tier);
                    } else if (newStatus == HealthStatus.HEALTHY) {
                        LOGGER.info("Server {} in tier {} recovered", server.getId(), tier);
                        metrics.recordHealthyServer(tier);
                    }
                }
            }
        }
        
        private HealthStatus performHealthCheck(ServerNode server) {
            // Simplified health check - would perform actual connectivity tests
            double random = ThreadLocalRandom.current().nextDouble();
            
            if (random < 0.05) { // 5% chance of unhealthy
                return HealthStatus.UNHEALTHY;
            } else if (random < 0.15) { // 10% chance of degraded
                return HealthStatus.DEGRADED;
            } else {
                return HealthStatus.HEALTHY;
            }
        }
    }
    
    private void collectMetrics() {
        // Collect and log comprehensive metrics
        StringBuilder metricsReport = new StringBuilder();
        metricsReport.append("\n=== MULTI-TIER LOAD BALANCER METRICS ===\n");
        
        for (LoadBalancerTier tier : LoadBalancerTier.values()) {
            TierManager manager = tierManagers.get(tier);
            metricsReport.append(String.format("Tier %s: %d servers (%d healthy)\n",
                tier, manager.getServerCount(), manager.getHealthyServerCount()));
        }
        
        metricsReport.append(String.format("Route Cache Hit Rate: %.2f%%\n",
            requestRouter.getCacheHitRate() * 100));
        
        metricsReport.append(metrics.getSummary());
        metricsReport.append("=====================================");
        
        LOGGER.info(metricsReport.toString());
    }
    
    // Data classes and enums
    public enum LoadBalancerTier {
        EDGE, APPLICATION, DATABASE, CACHE
    }
    
    public enum LoadBalancingStrategy {
        ROUND_ROBIN, WEIGHTED_ROUND_ROBIN, LEAST_CONNECTIONS, 
        LEAST_RESPONSE_TIME, CONSISTENT_HASH, GEOGRAPHIC_PROXIMITY
    }
    
    public enum HealthStatus {
        HEALTHY, DEGRADED, UNHEALTHY
    }
    
    public static class ServerNode {
        private final String id;
        private final String address;
        private final int port;
        private final String region;
        private final int weight;
        private volatile HealthStatus healthStatus;
        private final AtomicInteger activeConnections;
        private final AtomicLong totalRequests;
        private final AtomicLong responseTimeSum;
        
        public ServerNode(String id, String address, int port, String region, int weight) {
            this.id = id;
            this.address = address;
            this.port = port;
            this.region = region;
            this.weight = weight;
            this.healthStatus = HealthStatus.HEALTHY;
            this.activeConnections = new AtomicInteger(0);
            this.totalRequests = new AtomicLong(0);
            this.responseTimeSum = new AtomicLong(0);
        }
        
        public String getId() { return id; }
        public String getAddress() { return address; }
        public int getPort() { return port; }
        public String getRegion() { return region; }
        public int getWeight() { return weight; }
        public HealthStatus getHealthStatus() { return healthStatus; }
        public void setHealthStatus(HealthStatus status) { this.healthStatus = status; }
        public int getActiveConnections() { return activeConnections.get(); }
        public long getAverageResponseTime() { 
            long requests = totalRequests.get();
            return requests > 0 ? responseTimeSum.get() / requests : 0; 
        }
        
        public void recordRequest(long responseTime) {
            totalRequests.incrementAndGet();
            responseTimeSum.addAndGet(responseTime);
        }
        
        @Override
        public String toString() {
            return String.format("ServerNode{id='%s', address='%s:%d', region='%s', status=%s}",
                id, address, port, region, healthStatus);
        }
    }
    
    public static class LoadBalancingRequest {
        private final String id;
        private final String type;
        private final String routingKey;
        private final String clientRegion;
        private final Map<String, Object> attributes;
        
        public LoadBalancingRequest(String id, String type, String routingKey, String clientRegion) {
            this.id = id;
            this.type = type;
            this.routingKey = routingKey;
            this.clientRegion = clientRegion;
            this.attributes = new HashMap<>();
        }
        
        public String getId() { return id; }
        public String getType() { return type; }
        public String getRoutingKey() { return routingKey; }
        public String getClientRegion() { return clientRegion; }
        
        public boolean requiresDatabase() {
            return "database_query".equals(type) || "user_update".equals(type);
        }
        
        public boolean requiresCache() {
            return "cache_lookup".equals(type) || "session_data".equals(type);
        }
    }
    
    public static class LoadBalancingResult {
        public final String requestId;
        public final boolean success;
        public final ServerNode edgeServer;
        public final ServerNode appServer;
        public final ServerNode dataServer;
        public final long routingTimeNanos;
        public final String message;
        
        public LoadBalancingResult(String requestId, boolean success, ServerNode edgeServer,
                                 ServerNode appServer, ServerNode dataServer, 
                                 long routingTimeNanos, String message) {
            this.requestId = requestId;
            this.success = success;
            this.edgeServer = edgeServer;
            this.appServer = appServer;
            this.dataServer = dataServer;
            this.routingTimeNanos = routingTimeNanos;
            this.message = message;
        }
        
        @Override
        public String toString() {
            return String.format("LoadBalancingResult{requestId='%s', success=%s, routingTime=%.2fms}",
                requestId, success, routingTimeNanos / 1_000_000.0);
        }
    }
    
    private static class RouteCache {
        final String route;
        final long expirationTime;
        
        RouteCache(String route, long expirationTime) {
            this.route = route;
            this.expirationTime = expirationTime;
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() > expirationTime;
        }
    }
    
    private static class ScalingHistory {
        private final Queue<Double> loadHistory = new ConcurrentLinkedQueue<>();
        private static final int MAX_HISTORY = 100;
        
        void recordLoad(double load) {
            loadHistory.offer(load);
            while (loadHistory.size() > MAX_HISTORY) {
                loadHistory.poll();
            }
        }
        
        List<Double> getRecentLoads(int count) {
            return loadHistory.stream()
                .skip(Math.max(0, loadHistory.size() - count))
                .collect(Collectors.toList());
        }
    }
    
    private static class LoadBalancerMetrics {
        private final LongAdder successfulRoutings = new LongAdder();
        private final LongAdder failedRoutings = new LongAdder();
        private final LongAdder totalRoutingTime = new LongAdder();
        private final Map<LoadBalancerTier, LongAdder> scaleUps = new EnumMap<>(LoadBalancerTier.class);
        private final Map<LoadBalancerTier, LongAdder> scaleDowns = new EnumMap<>(LoadBalancerTier.class);
        
        LoadBalancerMetrics() {
            for (LoadBalancerTier tier : LoadBalancerTier.values()) {
                scaleUps.put(tier, new LongAdder());
                scaleDowns.put(tier, new LongAdder());
            }
        }
        
        void recordSuccessfulRouting(long routingTimeNanos) {
            successfulRoutings.increment();
            totalRoutingTime.add(routingTimeNanos);
        }
        
        void recordFailedRouting() {
            failedRoutings.increment();
        }
        
        void recordScaleUp(LoadBalancerTier tier) {
            scaleUps.get(tier).increment();
        }
        
        void recordScaleDown(LoadBalancerTier tier) {
            scaleDowns.get(tier).increment();
        }
        
        void recordUnhealthyServer(LoadBalancerTier tier) {
            // Could track per-tier health metrics
        }
        
        void recordHealthyServer(LoadBalancerTier tier) {
            // Could track per-tier health metrics
        }
        
        String getSummary() {
            long successful = successfulRoutings.sum();
            long failed = failedRoutings.sum();
            long total = successful + failed;
            
            double successRate = total > 0 ? (double) successful / total * 100 : 0;
            double avgRoutingTime = successful > 0 ? (double) totalRoutingTime.sum() / successful / 1_000_000.0 : 0;
            
            return String.format("Success Rate: %.2f%%, Avg Routing Time: %.2fms\n", 
                successRate, avgRoutingTime);
        }
    }
    
    // Public API methods
    public LoadBalancerMetrics getMetrics() {
        return metrics;
    }
    
    public int getTotalServers() {
        return tierManagers.values().stream()
            .mapToInt(TierManager::getServerCount)
            .sum();
    }
    
    public int getHealthyServers() {
        return tierManagers.values().stream()
            .mapToInt(TierManager::getHealthyServerCount)
            .sum();
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
        
        LOGGER.info("Multi-Tier Load Balancer shutdown completed");
    }
}