package com.eu.habbo.core.metrics;

import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe metric value container supporting different metric types
 */
public class MetricValue {
    
    private final MetricType type;
    private final LongAdder value = new LongAdder();
    private final LongAdder count = new LongAdder();
    private volatile long lastValue = 0;
    
    public MetricValue(MetricType type) {
        this.type = type;
    }
    
    /**
     * Add value (for counters and histograms)
     */
    public void addValue(long amount) {
        switch (type) {
            case COUNTER:
                value.add(amount);
                break;
            case HISTOGRAM:
                value.add(amount);
                count.increment();
                break;
            case GAUGE:
                setValue(amount); // Gauge just sets the value
                break;
        }
    }
    
    /**
     * Set value (for gauges)
     */
    public void setValue(long newValue) {
        this.lastValue = newValue;
        if (type == MetricType.GAUGE) {
            value.reset();
            value.add(newValue);
        }
    }
    
    /**
     * Get current value
     */
    public long getValue() {
        switch (type) {
            case COUNTER:
            case GAUGE:
                return value.sum();
            case HISTOGRAM:
                return count.sum() > 0 ? value.sum() / count.sum() : 0; // Average
            default:
                return value.sum();
        }
    }
    
    /**
     * Get total sum (useful for histograms)
     */
    public long getSum() {
        return value.sum();
    }
    
    /**
     * Get count (useful for histograms)
     */
    public long getCount() {
        return count.sum();
    }
    
    /**
     * Get metric type
     */
    public MetricType getType() {
        return type;
    }
    
    /**
     * Reset metric
     */
    public void reset() {
        value.reset();
        count.reset();
        lastValue = 0;
    }
    
    @Override
    public String toString() {
        return String.format("MetricValue{type=%s, value=%d, count=%d}", 
                           type, getValue(), getCount());
    }
}

/**
 * Metric types supported by the system
 */
enum MetricType {
    COUNTER,    // Monotonically increasing value
    GAUGE,      // Point-in-time value that can go up or down
    HISTOGRAM   // Collection of values with statistical analysis
}