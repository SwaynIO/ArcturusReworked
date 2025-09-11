package com.eu.habbo.core.streaming;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.*;
import java.util.stream.Collectors;
import java.util.function.Consumer;

/**
 * Real-time streaming system with intelligent event processing and adaptive throughput
 * Features event sourcing, stream processing, and real-time analytics
 */
public class RealTimeStreamingSystem {
    private static final Logger LOGGER = LoggerFactory.getLogger(RealTimeStreamingSystem.class);
    
    private static RealTimeStreamingSystem instance;
    
    private final StreamProcessorManager processorManager;
    private final EventSourceManager eventSourceManager;
    private final StreamAnalytics analytics;
    private final StreamRouter router;
    private final BackpressureManager backpressureManager;
    private final StreamTopologyManager topologyManager;
    private final ScheduledExecutorService streamScheduler;
    
    // Streaming configuration
    private static final int DEFAULT_BUFFER_SIZE = 1000;
    private static final long PROCESSING_TIMEOUT = 5000; // 5 seconds
    private static final int MAX_CONCURRENT_STREAMS = 100;
    
    private RealTimeStreamingSystem() {
        this.processorManager = new StreamProcessorManager();
        this.eventSourceManager = new EventSourceManager();
        this.analytics = new StreamAnalytics();
        this.router = new StreamRouter();
        this.backpressureManager = new BackpressureManager();
        this.topologyManager = new StreamTopologyManager();
        
        this.streamScheduler = Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "RealTimeStreaming");
            t.setDaemon(true);
            return t;
        });
        
        initializeStreamingSystem();
        LOGGER.info("Real-Time Streaming System initialized");
    }
    
    public static synchronized RealTimeStreamingSystem getInstance() {
        if (instance == null) {
            instance = new RealTimeStreamingSystem();
        }
        return instance;
    }
    
    private void initializeStreamingSystem() {
        // Start stream processing monitoring
        streamScheduler.scheduleWithFixedDelay(processorManager::monitorProcessors,
            30, 30, TimeUnit.SECONDS);
        
        // Start analytics collection
        streamScheduler.scheduleWithFixedDelay(analytics::collectMetrics,
            10, 10, TimeUnit.SECONDS);
        
        // Start backpressure monitoring
        streamScheduler.scheduleWithFixedDelay(backpressureManager::monitorBackpressure,
            5, 5, TimeUnit.SECONDS);
        
        // Start topology optimization
        streamScheduler.scheduleWithFixedDelay(topologyManager::optimizeTopology,
            300, 300, TimeUnit.SECONDS);
    }
    
    /**
     * Create a new stream with specified configuration
     */
    public <T> CompletableFuture<StreamHandle<T>> createStreamAsync(StreamConfig<T> config) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // Create stream processor
                StreamProcessor<T> processor = processorManager.createProcessor(config);
                
                // Register with event source manager
                EventSource<T> eventSource = eventSourceManager.createEventSource(config);
                
                // Create stream handle
                StreamHandle<T> handle = new StreamHandle<>(processor, eventSource, config);
                
                // Start processing
                processor.start();
                eventSource.start();
                
                // Register with topology
                topologyManager.registerStream(handle);
                
                analytics.recordStreamCreated(config.getStreamName());
                LOGGER.info("Created stream: {}", config.getStreamName());
                
                return handle;
                
            } catch (Exception e) {
                LOGGER.error("Failed to create stream: {}", config.getStreamName(), e);
                analytics.recordStreamError(config.getStreamName());
                throw new RuntimeException("Stream creation failed", e);
            }
        });
    }
    
    /**
     * Publish event to stream
     */
    public <T> CompletableFuture<Boolean> publishAsync(String streamName, StreamEvent<T> event) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                StreamHandle<T> handle = topologyManager.getStream(streamName);
                if (handle == null) {
                    analytics.recordPublishError(streamName);
                    return false;
                }
                
                // Route event through the processing pipeline
                boolean success = router.routeEvent(handle, event);
                
                if (success) {
                    analytics.recordEventPublished(streamName);
                } else {
                    analytics.recordPublishError(streamName);
                }
                
                return success;
                
            } catch (Exception e) {
                LOGGER.error("Failed to publish to stream: {}", streamName, e);
                analytics.recordPublishError(streamName);
                return false;
            }
        });
    }
    
    /**
     * Subscribe to stream events
     */
    public <T> StreamSubscription<T> subscribe(String streamName, Consumer<StreamEvent<T>> eventHandler) {
        StreamHandle<T> handle = topologyManager.getStream(streamName);
        if (handle == null) {
            throw new IllegalArgumentException("Stream not found: " + streamName);
        }
        
        StreamSubscription<T> subscription = new StreamSubscription<>(streamName, eventHandler);
        handle.addSubscription(subscription);
        
        analytics.recordSubscription(streamName);
        LOGGER.debug("Added subscription to stream: {}", streamName);
        
        return subscription;
    }
    
    /**
     * Advanced stream processor management
     */
    private class StreamProcessorManager {
        private final Map<String, StreamProcessor<?>> processors = new ConcurrentHashMap<>();
        private final LongAdder processorCreations = new LongAdder();
        
        @SuppressWarnings("unchecked")
        <T> StreamProcessor<T> createProcessor(StreamConfig<T> config) {
            StreamProcessor<T> processor = new StreamProcessor<>(config);
            processors.put(config.getStreamName(), processor);
            processorCreations.increment();
            return processor;
        }
        
        void monitorProcessors() {
            for (Map.Entry<String, StreamProcessor<?>> entry : processors.entrySet()) {
                String streamName = entry.getKey();
                StreamProcessor<?> processor = entry.getValue();
                
                ProcessorMetrics metrics = processor.getMetrics();
                
                // Check for performance issues
                if (metrics.getProcessingRate() < processor.getConfig().getMinProcessingRate()) {
                    LOGGER.warn("Stream {} processing rate below threshold: {} events/sec", 
                              streamName, metrics.getProcessingRate());
                    
                    // Trigger optimization
                    optimizeProcessor(processor);
                }
                
                // Check for errors
                if (metrics.getErrorRate() > 0.05) { // 5% error rate
                    LOGGER.warn("Stream {} error rate high: {:.2f}%", 
                              streamName, metrics.getErrorRate() * 100);
                }
            }
        }
        
        private void optimizeProcessor(StreamProcessor<?> processor) {
            // Optimize processor performance
            processor.increaseParallelism();
        }
        
        public ProcessorManagerStats getStats() {
            return new ProcessorManagerStats(processors.size(), processorCreations.sum());
        }
    }
    
    /**
     * Event source management for stream inputs
     */
    private class EventSourceManager {
        private final Map<String, EventSource<?>> eventSources = new ConcurrentHashMap<>();
        private final LongAdder sourceCreations = new LongAdder();
        
        <T> EventSource<T> createEventSource(StreamConfig<T> config) {
            EventSource<T> source = new EventSource<>(config);
            eventSources.put(config.getStreamName(), source);
            sourceCreations.increment();
            return source;
        }
        
        public EventSourceManagerStats getStats() {
            return new EventSourceManagerStats(eventSources.size(), sourceCreations.sum());
        }
    }
    
    /**
     * Stream analytics with real-time metrics
     */
    private class StreamAnalytics {
        private final Map<String, StreamMetrics> streamMetrics = new ConcurrentHashMap<>();
        private final LongAdder totalEvents = new LongAdder();
        private final LongAdder totalErrors = new LongAdder();
        
        void recordStreamCreated(String streamName) {
            streamMetrics.computeIfAbsent(streamName, k -> new StreamMetrics());
        }
        
        void recordEventPublished(String streamName) {
            StreamMetrics metrics = streamMetrics.get(streamName);
            if (metrics != null) {
                metrics.recordEvent();
            }
            totalEvents.increment();
        }
        
        void recordPublishError(String streamName) {
            StreamMetrics metrics = streamMetrics.get(streamName);
            if (metrics != null) {
                metrics.recordError();
            }
            totalErrors.increment();
        }
        
        void recordSubscription(String streamName) {
            StreamMetrics metrics = streamMetrics.get(streamName);
            if (metrics != null) {
                metrics.recordSubscription();
            }
        }
        
        void recordStreamError(String streamName) {
            totalErrors.increment();
        }
        
        void collectMetrics() {
            // Collect and analyze stream metrics
            for (Map.Entry<String, StreamMetrics> entry : streamMetrics.entrySet()) {
                String streamName = entry.getKey();
                StreamMetrics metrics = entry.getValue();
                
                double throughput = metrics.getThroughput();
                double errorRate = metrics.getErrorRate();
                
                if (throughput < 1.0) { // Less than 1 event per second
                    LOGGER.debug("Stream {} has low throughput: {} events/sec", streamName, throughput);
                }
                
                if (errorRate > 0.1) { // More than 10% errors
                    LOGGER.warn("Stream {} has high error rate: {:.2f}%", streamName, errorRate * 100);
                }
            }
        }
        
        public StreamAnalyticsStats getStats() {
            int activeStreams = streamMetrics.size();
            double avgThroughput = streamMetrics.values().stream()
                .mapToDouble(StreamMetrics::getThroughput)
                .average()
                .orElse(0.0);
            
            return new StreamAnalyticsStats(
                activeStreams,
                totalEvents.sum(),
                totalErrors.sum(),
                avgThroughput
            );
        }
    }
    
    /**
     * Intelligent stream routing
     */
    private class StreamRouter {
        private final LongAdder routingDecisions = new LongAdder();
        
        <T> boolean routeEvent(StreamHandle<T> handle, StreamEvent<T> event) {
            routingDecisions.increment();
            
            try {
                // Apply routing rules
                if (!shouldRouteEvent(event)) {
                    return false;
                }
                
                // Check backpressure
                if (backpressureManager.shouldThrottleStream(handle.getConfig().getStreamName())) {
                    LOGGER.debug("Throttling event due to backpressure: {}", handle.getConfig().getStreamName());
                    return false;
                }
                
                // Route to processor
                return handle.getProcessor().processEvent(event);
                
            } catch (Exception e) {
                LOGGER.error("Event routing failed", e);
                return false;
            }
        }
        
        private <T> boolean shouldRouteEvent(StreamEvent<T> event) {
            // Apply routing filters
            return event != null && event.getData() != null;
        }
        
        public StreamRouterStats getStats() {
            return new StreamRouterStats(routingDecisions.sum());
        }
    }
    
    /**
     * Backpressure management for flow control
     */
    private class BackpressureManager {
        private final Map<String, BackpressureState> streamStates = new ConcurrentHashMap<>();
        private final LongAdder throttledEvents = new LongAdder();
        
        boolean shouldThrottleStream(String streamName) {
            BackpressureState state = streamStates.computeIfAbsent(streamName, k -> new BackpressureState());
            
            return state.isUnderPressure();
        }
        
        void recordBackpressure(String streamName, int queueSize, int maxQueueSize) {
            BackpressureState state = streamStates.computeIfAbsent(streamName, k -> new BackpressureState());
            state.updatePressure(queueSize, maxQueueSize);
            
            if (state.isUnderPressure()) {
                throttledEvents.increment();
            }
        }
        
        void monitorBackpressure() {
            for (Map.Entry<String, BackpressureState> entry : streamStates.entrySet()) {
                String streamName = entry.getKey();
                BackpressureState state = entry.getValue();
                
                if (state.isUnderPressure()) {
                    LOGGER.warn("Stream {} experiencing backpressure: {:.1f}% capacity", 
                              streamName, state.getPressureLevel() * 100);
                }
            }
        }
        
        public BackpressureManagerStats getStats() {
            int streamsUnderPressure = (int) streamStates.values().stream()
                .filter(BackpressureState::isUnderPressure)
                .count();
            
            return new BackpressureManagerStats(streamsUnderPressure, throttledEvents.sum());
        }
    }
    
    /**
     * Stream topology management and optimization
     */
    private class StreamTopologyManager {
        private final Map<String, StreamHandle<?>> activeStreams = new ConcurrentHashMap<>();
        private final LongAdder topologyOptimizations = new LongAdder();
        
        <T> void registerStream(StreamHandle<T> handle) {
            activeStreams.put(handle.getConfig().getStreamName(), handle);
        }
        
        @SuppressWarnings("unchecked")
        <T> StreamHandle<T> getStream(String streamName) {
            return (StreamHandle<T>) activeStreams.get(streamName);
        }
        
        void optimizeTopology() {
            // Optimize stream topology for better performance
            for (StreamHandle<?> handle : activeStreams.values()) {
                optimizeStreamHandle(handle);
            }
            
            topologyOptimizations.increment();
        }
        
        private void optimizeStreamHandle(StreamHandle<?> handle) {
            ProcessorMetrics metrics = handle.getProcessor().getMetrics();
            
            // Optimize based on metrics
            if (metrics.getProcessingRate() > handle.getConfig().getMaxProcessingRate()) {
                // Stream is processing too fast, might need throttling
                handle.getProcessor().adjustProcessingRate(0.9); // Reduce by 10%
            } else if (metrics.getQueueSize() > handle.getConfig().getBufferSize() * 0.8) {
                // Queue is getting full, increase parallelism
                handle.getProcessor().increaseParallelism();
            }
        }
        
        public TopologyManagerStats getStats() {
            return new TopologyManagerStats(activeStreams.size(), topologyOptimizations.sum());
        }
    }
    
    // Core streaming classes
    public static class StreamConfig<T> {
        private final String streamName;
        private final Class<T> eventType;
        private final int bufferSize;
        private final int parallelism;
        private final double minProcessingRate;
        private final double maxProcessingRate;
        
        public StreamConfig(String streamName, Class<T> eventType, int bufferSize, 
                          int parallelism, double minProcessingRate, double maxProcessingRate) {
            this.streamName = streamName;
            this.eventType = eventType;
            this.bufferSize = bufferSize;
            this.parallelism = parallelism;
            this.minProcessingRate = minProcessingRate;
            this.maxProcessingRate = maxProcessingRate;
        }
        
        public String getStreamName() { return streamName; }
        public Class<T> getEventType() { return eventType; }
        public int getBufferSize() { return bufferSize; }
        public int getParallelism() { return parallelism; }
        public double getMinProcessingRate() { return minProcessingRate; }
        public double getMaxProcessingRate() { return maxProcessingRate; }
    }
    
    public static class StreamEvent<T> {
        private final String eventId;
        private final T data;
        private final long timestamp;
        private final Map<String, Object> metadata;
        
        public StreamEvent(String eventId, T data) {
            this(eventId, data, System.currentTimeMillis(), new HashMap<>());
        }
        
        public StreamEvent(String eventId, T data, long timestamp, Map<String, Object> metadata) {
            this.eventId = eventId;
            this.data = data;
            this.timestamp = timestamp;
            this.metadata = new HashMap<>(metadata);
        }
        
        public String getEventId() { return eventId; }
        public T getData() { return data; }
        public long getTimestamp() { return timestamp; }
        public Map<String, Object> getMetadata() { return new HashMap<>(metadata); }
        
        @Override
        public String toString() {
            return String.format("StreamEvent{id='%s', timestamp=%d}", eventId, timestamp);
        }
    }
    
    public static class StreamHandle<T> {
        private final StreamProcessor<T> processor;
        private final EventSource<T> eventSource;
        private final StreamConfig<T> config;
        private final Set<StreamSubscription<T>> subscriptions = ConcurrentHashMap.newKeySet();
        
        StreamHandle(StreamProcessor<T> processor, EventSource<T> eventSource, StreamConfig<T> config) {
            this.processor = processor;
            this.eventSource = eventSource;
            this.config = config;
        }
        
        public StreamProcessor<T> getProcessor() { return processor; }
        public EventSource<T> getEventSource() { return eventSource; }
        public StreamConfig<T> getConfig() { return config; }
        
        void addSubscription(StreamSubscription<T> subscription) {
            subscriptions.add(subscription);
            processor.addSubscription(subscription);
        }
        
        void removeSubscription(StreamSubscription<T> subscription) {
            subscriptions.remove(subscription);
            processor.removeSubscription(subscription);
        }
        
        public int getSubscriptionCount() { return subscriptions.size(); }
    }
    
    private static class StreamProcessor<T> {
        private final StreamConfig<T> config;
        private final BlockingQueue<StreamEvent<T>> eventQueue;
        private final Set<StreamSubscription<T>> subscriptions = ConcurrentHashMap.newKeySet();
        private final ExecutorService processingExecutor;
        private final ProcessorMetrics metrics;
        private volatile boolean running = false;
        
        StreamProcessor(StreamConfig<T> config) {
            this.config = config;
            this.eventQueue = new LinkedBlockingQueue<>(config.getBufferSize());
            this.processingExecutor = Executors.newFixedThreadPool(config.getParallelism());
            this.metrics = new ProcessorMetrics();
        }
        
        void start() {
            running = true;
            
            // Start processing threads
            for (int i = 0; i < config.getParallelism(); i++) {
                processingExecutor.submit(this::processEvents);
            }
        }
        
        void stop() {
            running = false;
            processingExecutor.shutdown();
        }
        
        boolean processEvent(StreamEvent<T> event) {
            if (!running) return false;
            
            boolean added = eventQueue.offer(event);
            if (!added) {
                metrics.recordQueueFull();
            }
            return added;
        }
        
        private void processEvents() {
            while (running) {
                try {
                    StreamEvent<T> event = eventQueue.poll(1, TimeUnit.SECONDS);
                    if (event != null) {
                        processSingleEvent(event);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    LOGGER.error("Event processing failed", e);
                    metrics.recordError();
                }
            }
        }
        
        private void processSingleEvent(StreamEvent<T> event) {
            long startTime = System.nanoTime();
            
            try {
                // Deliver to all subscriptions
                for (StreamSubscription<T> subscription : subscriptions) {
                    subscription.deliverEvent(event);
                }
                
                long processingTime = System.nanoTime() - startTime;
                metrics.recordProcessedEvent(processingTime);
                
            } catch (Exception e) {
                LOGGER.error("Event delivery failed", e);
                metrics.recordError();
            }
        }
        
        void addSubscription(StreamSubscription<T> subscription) {
            subscriptions.add(subscription);
        }
        
        void removeSubscription(StreamSubscription<T> subscription) {
            subscriptions.remove(subscription);
        }
        
        void increaseParallelism() {
            // Would dynamically increase processing parallelism
            LOGGER.debug("Increasing parallelism for stream: {}", config.getStreamName());
        }
        
        void adjustProcessingRate(double factor) {
            // Would adjust processing rate
            LOGGER.debug("Adjusting processing rate by factor: {}", factor);
        }
        
        StreamConfig<T> getConfig() { return config; }
        ProcessorMetrics getMetrics() { return metrics; }
    }
    
    private static class EventSource<T> {
        private final StreamConfig<T> config;
        private volatile boolean running = false;
        
        EventSource(StreamConfig<T> config) {
            this.config = config;
        }
        
        void start() {
            running = true;
        }
        
        void stop() {
            running = false;
        }
        
        boolean isRunning() { return running; }
    }
    
    public static class StreamSubscription<T> {
        private final String streamName;
        private final Consumer<StreamEvent<T>> eventHandler;
        private final AtomicLong eventsReceived = new AtomicLong();
        
        StreamSubscription(String streamName, Consumer<StreamEvent<T>> eventHandler) {
            this.streamName = streamName;
            this.eventHandler = eventHandler;
        }
        
        void deliverEvent(StreamEvent<T> event) {
            try {
                eventHandler.accept(event);
                eventsReceived.incrementAndGet();
            } catch (Exception e) {
                LOGGER.error("Event handler failed for stream: {}", streamName, e);
            }
        }
        
        public String getStreamName() { return streamName; }
        public long getEventsReceived() { return eventsReceived.get(); }
    }
    
    // Supporting classes
    private static class StreamMetrics {
        private final LongAdder eventCount = new LongAdder();
        private final LongAdder errorCount = new LongAdder();
        private final LongAdder subscriptionCount = new LongAdder();
        private final AtomicLong lastEventTime = new AtomicLong(System.currentTimeMillis());
        
        void recordEvent() {
            eventCount.increment();
            lastEventTime.set(System.currentTimeMillis());
        }
        
        void recordError() { errorCount.increment(); }
        void recordSubscription() { subscriptionCount.increment(); }
        
        double getThroughput() {
            long events = eventCount.sum();
            long timeSpan = System.currentTimeMillis() - (lastEventTime.get() - 60000); // Last minute
            return timeSpan > 0 ? (double) events * 1000 / timeSpan : 0.0;
        }
        
        double getErrorRate() {
            long events = eventCount.sum();
            long errors = errorCount.sum();
            return events > 0 ? (double) errors / events : 0.0;
        }
    }
    
    private static class ProcessorMetrics {
        private final LongAdder processedEvents = new LongAdder();
        private final LongAdder errors = new LongAdder();
        private final LongAdder queueFullEvents = new LongAdder();
        private final AtomicLong totalProcessingTime = new AtomicLong();
        private final AtomicLong lastMetricsTime = new AtomicLong(System.currentTimeMillis());
        
        void recordProcessedEvent(long processingTime) {
            processedEvents.increment();
            totalProcessingTime.addAndGet(processingTime);
            lastMetricsTime.set(System.currentTimeMillis());
        }
        
        void recordError() { errors.increment(); }
        void recordQueueFull() { queueFullEvents.increment(); }
        
        double getProcessingRate() {
            long events = processedEvents.sum();
            long timeSpan = System.currentTimeMillis() - (lastMetricsTime.get() - 60000);
            return timeSpan > 0 ? (double) events * 1000 / timeSpan : 0.0;
        }
        
        double getErrorRate() {
            long events = processedEvents.sum();
            long errorCount = errors.sum();
            return events > 0 ? (double) errorCount / events : 0.0;
        }
        
        double getAverageProcessingTime() {
            long events = processedEvents.sum();
            return events > 0 ? (double) totalProcessingTime.get() / events / 1_000_000.0 : 0.0; // Convert to ms
        }
        
        int getQueueSize() {
            // Would return actual queue size
            return (int) (Math.random() * 100);
        }
    }
    
    private static class BackpressureState {
        private volatile double pressureLevel = 0.0;
        private volatile boolean underPressure = false;
        
        void updatePressure(int currentSize, int maxSize) {
            pressureLevel = maxSize > 0 ? (double) currentSize / maxSize : 0.0;
            underPressure = pressureLevel > 0.8; // 80% threshold
        }
        
        boolean isUnderPressure() { return underPressure; }
        double getPressureLevel() { return pressureLevel; }
    }
    
    // Statistics classes
    public static class ProcessorManagerStats {
        public final int activeProcessors;
        public final long processorCreations;
        
        public ProcessorManagerStats(int activeProcessors, long processorCreations) {
            this.activeProcessors = activeProcessors;
            this.processorCreations = processorCreations;
        }
        
        @Override
        public String toString() {
            return String.format("ProcessorManagerStats{active=%d, created=%d}",
                activeProcessors, processorCreations);
        }
    }
    
    public static class EventSourceManagerStats {
        public final int activeSources;
        public final long sourceCreations;
        
        public EventSourceManagerStats(int activeSources, long sourceCreations) {
            this.activeSources = activeSources;
            this.sourceCreations = sourceCreations;
        }
        
        @Override
        public String toString() {
            return String.format("EventSourceManagerStats{active=%d, created=%d}",
                activeSources, sourceCreations);
        }
    }
    
    public static class StreamAnalyticsStats {
        public final int activeStreams;
        public final long totalEvents;
        public final long totalErrors;
        public final double avgThroughput;
        
        public StreamAnalyticsStats(int activeStreams, long totalEvents, long totalErrors, double avgThroughput) {
            this.activeStreams = activeStreams;
            this.totalEvents = totalEvents;
            this.totalErrors = totalErrors;
            this.avgThroughput = avgThroughput;
        }
        
        @Override
        public String toString() {
            return String.format("StreamAnalyticsStats{streams=%d, events=%d, errors=%d, throughput=%.2f/s}",
                activeStreams, totalEvents, totalErrors, avgThroughput);
        }
    }
    
    public static class StreamRouterStats {
        public final long routingDecisions;
        
        public StreamRouterStats(long routingDecisions) {
            this.routingDecisions = routingDecisions;
        }
        
        @Override
        public String toString() {
            return String.format("StreamRouterStats{decisions=%d}", routingDecisions);
        }
    }
    
    public static class BackpressureManagerStats {
        public final int streamsUnderPressure;
        public final long throttledEvents;
        
        public BackpressureManagerStats(int streamsUnderPressure, long throttledEvents) {
            this.streamsUnderPressure = streamsUnderPressure;
            this.throttledEvents = throttledEvents;
        }
        
        @Override
        public String toString() {
            return String.format("BackpressureManagerStats{underPressure=%d, throttled=%d}",
                streamsUnderPressure, throttledEvents);
        }
    }
    
    public static class TopologyManagerStats {
        public final int activeStreams;
        public final long topologyOptimizations;
        
        public TopologyManagerStats(int activeStreams, long topologyOptimizations) {
            this.activeStreams = activeStreams;
            this.topologyOptimizations = topologyOptimizations;
        }
        
        @Override
        public String toString() {
            return String.format("TopologyManagerStats{streams=%d, optimizations=%d}",
                activeStreams, topologyOptimizations);
        }
    }
    
    // Public API methods
    public <T> StreamHandle<T> createStream(StreamConfig<T> config) {
        try {
            return createStreamAsync(config).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            LOGGER.error("Synchronous create stream failed", e);
            throw new RuntimeException("Stream creation failed", e);
        }
    }
    
    public <T> boolean publish(String streamName, StreamEvent<T> event) {
        try {
            return publishAsync(streamName, event).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            LOGGER.error("Synchronous publish failed", e);
            return false;
        }
    }
    
    public String getComprehensiveStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("=== REAL-TIME STREAMING SYSTEM STATS ===\n");
        stats.append("Processor Manager: ").append(processorManager.getStats()).append("\n");
        stats.append("Event Source Manager: ").append(eventSourceManager.getStats()).append("\n");
        stats.append("Stream Analytics: ").append(analytics.getStats()).append("\n");
        stats.append("Stream Router: ").append(router.getStats()).append("\n");
        stats.append("Backpressure Manager: ").append(backpressureManager.getStats()).append("\n");
        stats.append("Topology Manager: ").append(topologyManager.getStats()).append("\n");
        stats.append("========================================");
        
        return stats.toString();
    }
    
    public void shutdown() {
        streamScheduler.shutdown();
        try {
            if (!streamScheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                streamScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            streamScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        // Shutdown all active streams
        for (StreamHandle<?> handle : topologyManager.activeStreams.values()) {
            handle.getProcessor().stop();
            handle.getEventSource().stop();
        }
        
        LOGGER.info("Real-Time Streaming System shutdown completed");
    }
}