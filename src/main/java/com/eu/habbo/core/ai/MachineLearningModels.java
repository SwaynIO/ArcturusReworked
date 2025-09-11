package com.eu.habbo.core.ai;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Machine learning models for load prediction
 * Implements lightweight ML algorithms optimized for server environments
 */
public class MachineLearningModels {
    
    /**
     * Linear regression predictor for trend analysis
     */
    public static class LinearRegressionPredictor {
        private volatile double slope = 0.0;
        private volatile double intercept = 0.0;
        private volatile long lastTraining = 0;
        
        public double predict(List<LoadPredictionEngine.LoadDataPoint> data, int minutesAhead) {
            if (data.size() < 2) return 0.5; // Default neutral prediction
            
            // Retrain if data has changed significantly
            if (System.currentTimeMillis() - lastTraining > 300000) { // 5 minutes
                trainModel(data);
            }
            
            // Predict using linear model: y = mx + b
            double timeOffset = minutesAhead / 60.0; // Convert to hours
            double prediction = slope * timeOffset + intercept;
            
            return Math.max(0.0, Math.min(1.0, prediction));
        }
        
        private void trainModel(List<LoadPredictionEngine.LoadDataPoint> data) {
            int n = data.size();
            if (n < 2) return;
            
            // Simple linear regression using least squares
            double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
            
            for (int i = 0; i < n; i++) {
                double x = i; // Time index
                double y = data.get(i).overallLoad;
                
                sumX += x;
                sumY += y;
                sumXY += x * y;
                sumX2 += x * x;
            }
            
            // Calculate slope and intercept
            double denominator = n * sumX2 - sumX * sumX;
            if (Math.abs(denominator) > 1e-10) {
                slope = (n * sumXY - sumX * sumY) / denominator;
                intercept = (sumY - slope * sumX) / n;
            }
            
            lastTraining = System.currentTimeMillis();
        }
        
        public double getSlope() { return slope; }
        public double getIntercept() { return intercept; }
    }
    
    /**
     * Seasonal pattern predictor for cyclic load patterns
     */
    public static class SeasonalPredictor {
        private final Map<Integer, Double> hourlyPatterns = new ConcurrentHashMap<>();
        private final Map<Integer, Double> dailyPatterns = new ConcurrentHashMap<>();
        private volatile long lastUpdate = 0;
        
        public double predict(List<LoadPredictionEngine.LoadDataPoint> data, int minutesAhead) {
            if (data.isEmpty()) return 0.5;
            
            // Update patterns periodically
            if (System.currentTimeMillis() - lastUpdate > 3600000) { // 1 hour
                updatePatterns(data);
            }
            
            // Get target time
            long targetTime = System.currentTimeMillis() + (minutesAhead * 60000L);
            java.util.Calendar cal = java.util.Calendar.getInstance();
            cal.setTimeInMillis(targetTime);
            
            int hourOfDay = cal.get(java.util.Calendar.HOUR_OF_DAY);
            int dayOfWeek = cal.get(java.util.Calendar.DAY_OF_WEEK);
            
            // Combine hourly and daily patterns
            double hourlyPrediction = hourlyPatterns.getOrDefault(hourOfDay, 0.5);
            double dailyPrediction = dailyPatterns.getOrDefault(dayOfWeek, 0.5);
            
            // Weighted average (hourly patterns are more granular)
            return hourlyPrediction * 0.7 + dailyPrediction * 0.3;
        }
        
        private void updatePatterns(List<LoadPredictionEngine.LoadDataPoint> data) {
            // Clear old patterns
            hourlyPatterns.clear();
            dailyPatterns.clear();
            
            // Group data by hour and day
            Map<Integer, java.util.List<Double>> hourlyData = new ConcurrentHashMap<>();
            Map<Integer, java.util.List<Double>> dailyData = new ConcurrentHashMap<>();
            
            for (LoadPredictionEngine.LoadDataPoint point : data) {
                java.util.Calendar cal = java.util.Calendar.getInstance();
                cal.setTimeInMillis(point.timestamp);
                
                int hour = cal.get(java.util.Calendar.HOUR_OF_DAY);
                int day = cal.get(java.util.Calendar.DAY_OF_WEEK);
                
                hourlyData.computeIfAbsent(hour, k -> new java.util.ArrayList<>()).add(point.overallLoad);
                dailyData.computeIfAbsent(day, k -> new java.util.ArrayList<>()).add(point.overallLoad);
            }
            
            // Calculate averages for each pattern
            hourlyData.forEach((hour, loads) -> {
                double average = loads.stream().mapToDouble(Double::doubleValue).average().orElse(0.5);
                hourlyPatterns.put(hour, average);
            });
            
            dailyData.forEach((day, loads) -> {
                double average = loads.stream().mapToDouble(Double::doubleValue).average().orElse(0.5);
                dailyPatterns.put(day, average);
            });
            
            lastUpdate = System.currentTimeMillis();
        }
        
        public Map<Integer, Double> getHourlyPatterns() { return new ConcurrentHashMap<>(hourlyPatterns); }
        public Map<Integer, Double> getDailyPatterns() { return new ConcurrentHashMap<>(dailyPatterns); }
    }
    
    /**
     * Anomaly detector using statistical methods
     */
    public static class AnomalyDetector {
        private volatile double mean = 0.0;
        private volatile double stdDev = 0.0;
        private volatile double threshold = 2.5; // Standard deviations for anomaly
        private final RingBuffer<Double> recentValues = new RingBuffer<>(100);
        
        public boolean detectAnomaly(LoadPredictionEngine.LoadDataPoint dataPoint) {
            double value = dataPoint.overallLoad;
            
            // Add to recent values for statistics
            recentValues.add(value);
            
            // Update statistics
            updateStatistics();
            
            // Check if value is anomalous (beyond threshold standard deviations)
            if (stdDev > 0.001) { // Avoid division by zero
                double zScore = Math.abs(value - mean) / stdDev;
                return zScore > threshold;
            }
            
            return false; // Can't determine without sufficient variance
        }
        
        private void updateStatistics() {
            List<Double> values = recentValues.getAllData();
            if (values.size() < 5) return; // Need minimum data for statistics
            
            // Calculate mean
            mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            
            // Calculate standard deviation
            double variance = values.stream()
                .mapToDouble(v -> Math.pow(v - mean, 2))
                .average().orElse(0.0);
            stdDev = Math.sqrt(variance);
        }
        
        public void setThreshold(double threshold) {
            this.threshold = Math.max(1.0, Math.min(5.0, threshold));
        }
        
        public double getMean() { return mean; }
        public double getStdDev() { return stdDev; }
        public double getThreshold() { return threshold; }
    }
    
    /**
     * Time series data for individual metrics
     */
    public static class MetricTimeSeries {
        private final RingBuffer<TimeSeriesPoint> data;
        private final String metricName;
        
        public MetricTimeSeries(String metricName, int capacity) {
            this.metricName = metricName;
            this.data = new RingBuffer<>(capacity);
        }
        
        public void addDataPoint(long timestamp, double value) {
            data.add(new TimeSeriesPoint(timestamp, value));
        }
        
        public List<TimeSeriesPoint> getData(int count) {
            return data.getRecentData(count);
        }
        
        public double getAverage(int recentCount) {
            List<TimeSeriesPoint> recent = data.getRecentData(recentCount);
            return recent.stream()
                .mapToDouble(p -> p.value)
                .average().orElse(0.0);
        }
        
        public double getTrend(int recentCount) {
            List<TimeSeriesPoint> recent = data.getRecentData(Math.min(recentCount, data.size()));
            if (recent.size() < 2) return 0.0;
            
            double firstHalf = recent.subList(0, recent.size()/2).stream()
                .mapToDouble(p -> p.value).average().orElse(0.0);
            double secondHalf = recent.subList(recent.size()/2, recent.size()).stream()
                .mapToDouble(p -> p.value).average().orElse(0.0);
            
            return secondHalf - firstHalf;
        }
        
        public String getMetricName() { return metricName; }
        public int size() { return data.size(); }
    }
    
    /**
     * Time series data point
     */
    public static class TimeSeriesPoint {
        public final long timestamp;
        public final double value;
        
        public TimeSeriesPoint(long timestamp, double value) {
            this.timestamp = timestamp;
            this.value = value;
        }
        
        @Override
        public String toString() {
            return String.format("TimeSeriesPoint{timestamp=%d, value=%.3f}", timestamp, value);
        }
    }
}