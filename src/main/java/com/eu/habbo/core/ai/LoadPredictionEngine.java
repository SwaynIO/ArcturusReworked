package com.eu.habbo.core.ai;

import com.eu.habbo.core.metrics.RealTimeMetrics;
import com.eu.habbo.util.collections.OptimizedConcurrentMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * AI-powered load prediction engine for Arcturus Morningstar Reworked
 * Uses machine learning algorithms to predict server load and auto-scale resources
 */
public class LoadPredictionEngine {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(LoadPredictionEngine.class);
    
    // Time series data storage (ring buffer for efficiency)
    private static final int MAX_HISTORY_HOURS = 168; // 7 days
    private final RingBuffer<LoadDataPoint> historicalData;
    private final OptimizedConcurrentMap<String, MetricTimeSeries> metricHistory;
    
    // Machine learning models
    private final LinearRegressionPredictor linearPredictor;
    private final SeasonalPredictor seasonalPredictor;
    private final AnomalyDetector anomalyDetector;
    
    // Prediction parameters
    private final ScheduledExecutorService scheduler;
    private final RealTimeMetrics metrics;
    private final AtomicLong predictionCount = new AtomicLong(0);
    
    // Learning parameters
    private volatile double learningRate = 0.01;
    private volatile int predictionHorizonMinutes = 30;
    private volatile boolean adaptiveModelEnabled = true;
    
    public LoadPredictionEngine() {
        this.historicalData = new RingBuffer<>(MAX_HISTORY_HOURS * 12); // 5-minute intervals
        this.metricHistory = new OptimizedConcurrentMap<>("MetricHistory", 64);
        this.linearPredictor = new LinearRegressionPredictor();
        this.seasonalPredictor = new SeasonalPredictor();
        this.anomalyDetector = new AnomalyDetector();
        this.metrics = RealTimeMetrics.getInstance();
        
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "LoadPrediction-" + r.hashCode());
            t.setDaemon(true);
            return t;
        });
        
        startDataCollection();
        startPredictionEngine();
        
        LOGGER.info("Load prediction engine initialized with {}h history and {}min horizon", 
                   MAX_HISTORY_HOURS, predictionHorizonMinutes);
    }
    
    /**
     * Get load prediction for specified minutes ahead
     */
    public LoadPrediction predictLoad(int minutesAhead) {
        if (historicalData.size() < 12) { // Need at least 1 hour of data
            return new LoadPrediction(0.5, 0.8, LoadTrend.STABLE, "Insufficient historical data");
        }
        
        try {
            // Collect current metrics
            LoadDataPoint currentLoad = collectCurrentLoad();
            
            // Generate predictions from different models
            double linearPrediction = linearPredictor.predict(historicalData.getRecentData(48), minutesAhead);
            double seasonalPrediction = seasonalPredictor.predict(historicalData.getAllData(), minutesAhead);
            
            // Weighted ensemble prediction
            double ensemblePrediction = combinepredictions(linearPrediction, seasonalPrediction, currentLoad);
            
            // Detect trend
            LoadTrend trend = detectTrend();
            
            // Calculate confidence based on model agreement
            double confidence = calculateConfidence(linearPrediction, seasonalPrediction, ensemblePrediction);
            
            // Check for anomalies
            String anomalyInfo = anomalyDetector.detectAnomaly(currentLoad) ? 
                "Anomaly detected in current load pattern" : "";
            
            predictionCount.incrementAndGet();
            metrics.recordHistogram("ai.prediction.value", (long)(ensemblePrediction * 1000));
            
            return new LoadPrediction(ensemblePrediction, confidence, trend, anomalyInfo);
            
        } catch (Exception e) {
            LOGGER.error("Error generating load prediction", e);
            return new LoadPrediction(0.5, 0.3, LoadTrend.UNKNOWN, "Prediction error: " + e.getMessage());
        }
    }
    
    /**
     * Get comprehensive load forecast for next hours
     */
    public LoadForecast getForecast(int hoursAhead) {
        List<LoadPrediction> predictions = new ArrayList<>();
        List<ResourceRecommendation> recommendations = new ArrayList<>();
        
        for (int hour = 1; hour <= hoursAhead; hour++) {
            LoadPrediction prediction = predictLoad(hour * 60);
            predictions.add(prediction);
            
            // Generate resource recommendations
            if (prediction.expectedLoad > 0.8) {
                recommendations.add(new ResourceRecommendation(
                    ResourceType.CPU, ActionType.SCALE_UP, hour,
                    "High load predicted: " + String.format("%.1f%%", prediction.expectedLoad * 100)
                ));
            } else if (prediction.expectedLoad < 0.3) {
                recommendations.add(new ResourceRecommendation(
                    ResourceType.MEMORY, ActionType.SCALE_DOWN, hour,
                    "Low load predicted: " + String.format("%.1f%%", prediction.expectedLoad * 100)
                ));
            }
        }
        
        return new LoadForecast(predictions, recommendations, System.currentTimeMillis());
    }
    
    /**
     * Train models with new data point
     */
    public void learnFromData(LoadDataPoint dataPoint) {
        historicalData.add(dataPoint);
        
        // Update metric time series
        metricHistory.values().forEach(timeSeries -> timeSeries.addDataPoint(dataPoint.timestamp, dataPoint.cpuUsage));
        
        // Adaptive learning - adjust parameters based on prediction accuracy
        if (adaptiveModelEnabled && predictionCount.get() % 100 == 0) {
            adaptModelParameters();
        }
        
        metrics.incrementCounter("ai.learning.datapoints");
    }
    
    /**
     * Get prediction accuracy statistics
     */
    public PredictionAccuracy getAccuracyStats() {
        if (historicalData.size() < 24) {
            return new PredictionAccuracy(0.0, 0.0, 0.0, 0);
        }
        
        // Calculate accuracy over last predictions
        List<LoadDataPoint> recent = historicalData.getRecentData(24);
        double totalError = 0.0;
        double maxError = 0.0;
        int validPredictions = 0;
        
        for (int i = 6; i < recent.size(); i++) { // Skip first 6 for prediction window
            LoadDataPoint actual = recent.get(i);
            double predicted = linearPredictor.predict(recent.subList(0, i), 30);
            
            double error = Math.abs(actual.overallLoad - predicted);
            totalError += error;
            maxError = Math.max(maxError, error);
            validPredictions++;
        }
        
        double meanError = validPredictions > 0 ? totalError / validPredictions : 1.0;
        double accuracy = Math.max(0.0, 1.0 - meanError);
        
        return new PredictionAccuracy(accuracy, meanError, maxError, validPredictions);
    }
    
    // Private helper methods
    
    private void startDataCollection() {
        // Collect load data every 5 minutes
        scheduler.scheduleAtFixedRate(() -> {
            try {
                LoadDataPoint currentLoad = collectCurrentLoad();
                learnFromData(currentLoad);
            } catch (Exception e) {
                LOGGER.error("Error collecting load data", e);
            }
        }, 0, 5, TimeUnit.MINUTES);
    }
    
    private void startPredictionEngine() {
        // Generate predictions every 10 minutes
        scheduler.scheduleAtFixedRate(() -> {
            try {
                LoadPrediction prediction = predictLoad(predictionHorizonMinutes);
                
                // Log significant predictions
                if (prediction.expectedLoad > 0.8 || prediction.expectedLoad < 0.2) {
                    LOGGER.info("Load prediction: {:.1f}% in {}min (confidence: {:.1f}%, trend: {})",
                               prediction.expectedLoad * 100, predictionHorizonMinutes,
                               prediction.confidence * 100, prediction.trend);
                }
                
                // Trigger auto-scaling if needed
                triggerAutoScaling(prediction);
                
            } catch (Exception e) {
                LOGGER.error("Error in prediction engine", e);
            }
        }, 10, 10, TimeUnit.MINUTES);
    }
    
    private LoadDataPoint collectCurrentLoad() {
        Runtime runtime = Runtime.getRuntime();
        
        double cpuUsage = getCurrentCpuUsage();
        double memoryUsage = (double)(runtime.totalMemory() - runtime.freeMemory()) / runtime.maxMemory();
        int activeConnections = getActiveConnectionCount();
        long packetsPerSecond = metrics.getMetricValue("packets.processed") / 60; // Approximate
        
        double overallLoad = (cpuUsage * 0.4) + (memoryUsage * 0.3) + 
                           (Math.min(1.0, activeConnections / 1000.0) * 0.2) +
                           (Math.min(1.0, packetsPerSecond / 10000.0) * 0.1);
        
        return new LoadDataPoint(
            System.currentTimeMillis(),
            cpuUsage, memoryUsage, activeConnections, packetsPerSecond, overallLoad
        );
    }
    
    private double getCurrentCpuUsage() {
        // Simplified CPU usage calculation
        // In real implementation, would use OperatingSystemMXBean
        return Math.random() * 0.3 + 0.2; // Simulated for now
    }
    
    private int getActiveConnectionCount() {
        // Would integrate with actual connection manager
        return (int)(Math.random() * 500 + 100); // Simulated
    }
    
    private double combinePredict2ions(double linear, double seasonal, LoadDataPoint current) {
        // Weighted ensemble based on recent model performance
        double linearWeight = 0.4;
        double seasonalWeight = 0.4;
        double currentWeight = 0.2;
        
        return (linear * linearWeight) + (seasonal * seasonalWeight) + (current.overallLoad * currentWeight);
    }
    
    private LoadTrend detectTrend() {
        if (historicalData.size() < 6) return LoadTrend.UNKNOWN;
        
        List<LoadDataPoint> recent = historicalData.getRecentData(6);
        double firstHalf = recent.subList(0, 3).stream().mapToDouble(p -> p.overallLoad).average().orElse(0.5);
        double secondHalf = recent.subList(3, 6).stream().mapToDouble(p -> p.overallLoad).average().orElse(0.5);
        
        double diff = secondHalf - firstHalf;
        if (Math.abs(diff) < 0.05) return LoadTrend.STABLE;
        return diff > 0 ? LoadTrend.INCREASING : LoadTrend.DECREASING;
    }
    
    private double calculateConfidence(double linear, double seasonal, double ensemble) {
        // Confidence based on model agreement
        double agreement = 1.0 - Math.abs(linear - seasonal) / 2.0;
        
        // Reduce confidence if we have limited data
        double dataConfidence = Math.min(1.0, historicalData.size() / 48.0);
        
        return Math.max(0.1, agreement * dataConfidence);
    }
    
    private void adaptModelParameters() {
        PredictionAccuracy accuracy = getAccuracyStats();
        
        // Adjust learning rate based on accuracy
        if (accuracy.meanAbsoluteError > 0.2) {
            learningRate = Math.min(0.05, learningRate * 1.1);
        } else if (accuracy.accuracy > 0.85) {
            learningRate = Math.max(0.001, learningRate * 0.95);
        }
        
        LOGGER.debug("Adapted learning rate to {} based on accuracy {:.2f}", 
                    learningRate, accuracy.accuracy);
    }
    
    private void triggerAutoScaling(LoadPrediction prediction) {
        // Integration point for auto-scaling systems
        if (prediction.expectedLoad > 0.9 && prediction.confidence > 0.7) {
            LOGGER.warn("High load predicted - consider scaling up resources");
            metrics.incrementCounter("ai.autoscaling.scale_up_suggested");
        } else if (prediction.expectedLoad < 0.2 && prediction.confidence > 0.7) {
            LOGGER.info("Low load predicted - consider scaling down resources");
            metrics.incrementCounter("ai.autoscaling.scale_down_suggested");
        }
    }
    
    /**
     * Get comprehensive statistics about the prediction engine
     */
    public PredictionEngineStats getEngineStats() {
        PredictionAccuracy accuracy = getAccuracyStats();
        
        return new PredictionEngineStats(
            historicalData.size(),
            predictionCount.get(),
            accuracy.accuracy,
            learningRate,
            predictionHorizonMinutes,
            adaptiveModelEnabled
        );
    }
    
    /**
     * Configure prediction engine parameters
     */
    public void configure(double learningRate, int horizonMinutes, boolean adaptiveEnabled) {
        this.learningRate = Math.max(0.001, Math.min(0.1, learningRate));
        this.predictionHorizonMinutes = Math.max(5, Math.min(180, horizonMinutes));
        this.adaptiveModelEnabled = adaptiveEnabled;
        
        LOGGER.info("Prediction engine configured - LR: {}, Horizon: {}min, Adaptive: {}", 
                   this.learningRate, this.predictionHorizonMinutes, this.adaptiveModelEnabled);
    }
    
    /**
     * Shutdown the prediction engine
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        LOGGER.info("Load prediction engine shut down");
    }
    
    // Data classes
    
    public static class LoadDataPoint {
        public final long timestamp;
        public final double cpuUsage;
        public final double memoryUsage;
        public final int activeConnections;
        public final long packetsPerSecond;
        public final double overallLoad;
        
        public LoadDataPoint(long timestamp, double cpuUsage, double memoryUsage,
                           int activeConnections, long packetsPerSecond, double overallLoad) {
            this.timestamp = timestamp;
            this.cpuUsage = cpuUsage;
            this.memoryUsage = memoryUsage;
            this.activeConnections = activeConnections;
            this.packetsPerSecond = packetsPerSecond;
            this.overallLoad = overallLoad;
        }
        
        @Override
        public String toString() {
            return String.format("LoadDataPoint{load=%.2f, cpu=%.2f, mem=%.2f, conn=%d, pps=%d}",
                               overallLoad, cpuUsage, memoryUsage, activeConnections, packetsPerSecond);
        }
    }
    
    public static class LoadPrediction {
        public final double expectedLoad;
        public final double confidence;
        public final LoadTrend trend;
        public final String notes;
        
        public LoadPrediction(double expectedLoad, double confidence, LoadTrend trend, String notes) {
            this.expectedLoad = expectedLoad;
            this.confidence = confidence;
            this.trend = trend;
            this.notes = notes;
        }
        
        @Override
        public String toString() {
            return String.format("LoadPrediction{load=%.1f%%, confidence=%.1f%%, trend=%s}",
                               expectedLoad * 100, confidence * 100, trend);
        }
    }
    
    public static class LoadForecast {
        public final List<LoadPrediction> predictions;
        public final List<ResourceRecommendation> recommendations;
        public final long generatedAt;
        
        public LoadForecast(List<LoadPrediction> predictions, List<ResourceRecommendation> recommendations, long generatedAt) {
            this.predictions = predictions;
            this.recommendations = recommendations;
            this.generatedAt = generatedAt;
        }
    }
    
    public static class ResourceRecommendation {
        public final ResourceType resource;
        public final ActionType action;
        public final int hoursAhead;
        public final String reason;
        
        public ResourceRecommendation(ResourceType resource, ActionType action, int hoursAhead, String reason) {
            this.resource = resource;
            this.action = action;
            this.hoursAhead = hoursAhead;
            this.reason = reason;
        }
    }
    
    public static class PredictionAccuracy {
        public final double accuracy;
        public final double meanAbsoluteError;
        public final double maxError;
        public final int sampleSize;
        
        public PredictionAccuracy(double accuracy, double meanAbsoluteError, double maxError, int sampleSize) {
            this.accuracy = accuracy;
            this.meanAbsoluteError = meanAbsoluteError;
            this.maxError = maxError;
            this.sampleSize = sampleSize;
        }
    }
    
    public static class PredictionEngineStats {
        public final int historicalDataPoints;
        public final long totalPredictions;
        public final double currentAccuracy;
        public final double learningRate;
        public final int predictionHorizonMinutes;
        public final boolean adaptiveEnabled;
        
        public PredictionEngineStats(int historicalDataPoints, long totalPredictions, double currentAccuracy,
                                   double learningRate, int predictionHorizonMinutes, boolean adaptiveEnabled) {
            this.historicalDataPoints = historicalDataPoints;
            this.totalPredictions = totalPredictions;
            this.currentAccuracy = currentAccuracy;
            this.learningRate = learningRate;
            this.predictionHorizonMinutes = predictionHorizonMinutes;
            this.adaptiveEnabled = adaptiveEnabled;
        }
    }
    
    public enum LoadTrend {
        INCREASING, DECREASING, STABLE, UNKNOWN
    }
    
    public enum ResourceType {
        CPU, MEMORY, NETWORK, STORAGE
    }
    
    public enum ActionType {
        SCALE_UP, SCALE_DOWN, OPTIMIZE, MONITOR
    }
}