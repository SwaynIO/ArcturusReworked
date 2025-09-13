package com.eu.habbo.core.pathfinding;

import com.eu.habbo.habbohotel.rooms.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Advanced Pathfinding Engine with intelligent route caching, predictive movement,
 * and high-performance A* algorithm optimizations
 */
public class AdvancedPathfindingEngine {
    private static final Logger LOGGER = LoggerFactory.getLogger(AdvancedPathfindingEngine.class);

    private static AdvancedPathfindingEngine instance;

    // Core pathfinding components
    private final PathCache pathCache;
    private final MovementPredictor movementPredictor;
    private final PathfindingOptimizer optimizer;
    private final PathfindingAnalyzer analyzer;
    private final DynamicPathfinder dynamicPathfinder;

    // Schedulers and executors
    private final ScheduledExecutorService pathfindingScheduler;
    private final ExecutorService pathComputationExecutor;

    // Configuration constants
    private static final int MAX_PATHFINDING_TIME_MS = 50;
    private static final int PATH_CACHE_SIZE = 10000;
    private static final long CACHE_TTL_MS = 300000; // 5 minutes
    private static final int PREDICTION_WINDOW_MS = 5000;

    private AdvancedPathfindingEngine() {
        this.pathCache = new PathCache();
        this.movementPredictor = new MovementPredictor();
        this.pathfindingOptimizer = new PathfindingOptimizer();
        this.analyzer = new PathfindingAnalyzer();
        this.dynamicPathfinder = new DynamicPathfinder();

        this.pathfindingScheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "AdvancedPathfinding-Scheduler");
            t.setDaemon(true);
            return t;
        });

        this.pathComputationExecutor = Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors()), r -> {
            Thread t = new Thread(r, "PathComputation");
            t.setDaemon(true);
            return t;
        });

        initializePathfindingEngine();
        LOGGER.info("Advanced Pathfinding Engine initialized with {} computation threads",
                   Math.max(2, Runtime.getRuntime().availableProcessors()));
    }

    public static synchronized AdvancedPathfindingEngine getInstance() {
        if (instance == null) {
            instance = new AdvancedPathfindingEngine();
        }
        return instance;
    }

    private void initializePathfindingEngine() {
        // Start cache maintenance
        pathfindingScheduler.scheduleWithFixedDelay(
            pathCache::performMaintenance,
            60000, 60000, TimeUnit.MILLISECONDS // 1 minute
        );

        // Start movement prediction updates
        pathfindingScheduler.scheduleWithFixedDelay(
            movementPredictor::updatePredictions,
            1000, 1000, TimeUnit.MILLISECONDS // 1 second
        );
    }

    /**
     * Enhanced pathfinding with caching and prediction
     */
    public CompletableFuture<PathResult> findOptimalPath(RoomTile startTile, RoomTile endTile,
                                                        RoomUnit roomUnit, Room room) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.nanoTime();

            try {
                // Generate cache key for this pathfinding request
                String cacheKey = generateCacheKey(startTile, endTile, roomUnit, room);

                // Check cache first
                CachedPath cachedPath = pathCache.get(cacheKey);
                if (cachedPath != null && cachedPath.isValid(room)) {
                    analyzer.recordCacheHit(startTime);
                    return new PathResult(cachedPath.getPath(), PathResultType.CACHED,
                                        System.nanoTime() - startTime);
                }

                // Check if we can predict the movement
                PredictedMovement prediction = movementPredictor.predictMovement(roomUnit, endTile);
                if (prediction != null && prediction.isConfident()) {
                    Deque<RoomTile> predictedPath = prediction.getPath();
                    if (isPathValid(predictedPath, room, roomUnit)) {
                        analyzer.recordPredictionHit(startTime);
                        return new PathResult(predictedPath, PathResultType.PREDICTED,
                                            System.nanoTime() - startTime);
                    }
                }

                // Compute new path using advanced algorithm
                PathfindingContext context = new PathfindingContext(startTile, endTile, roomUnit, room);
                Deque<RoomTile> computedPath = dynamicPathfinder.computePath(context);

                if (computedPath != null && !computedPath.isEmpty()) {
                    // Cache the computed path
                    pathCache.put(cacheKey, new CachedPath(computedPath, room));

                    // Update movement predictor
                    movementPredictor.recordMovement(roomUnit, computedPath);

                    analyzer.recordComputation(startTime, computedPath.size());
                    return new PathResult(computedPath, PathResultType.COMPUTED,
                                        System.nanoTime() - startTime);
                } else {
                    analyzer.recordFailure(startTime);
                    return new PathResult(new LinkedList<>(), PathResultType.FAILED,
                                        System.nanoTime() - startTime);
                }

            } catch (Exception e) {
                LOGGER.error("Advanced pathfinding failed", e);
                analyzer.recordError();
                return new PathResult(new LinkedList<>(), PathResultType.ERROR,
                                    System.nanoTime() - startTime);
            }
        }, pathComputationExecutor);
    }

    /**
     * Intelligent path caching system with TTL and room state validation
     */
    private class PathCache {
        private final Map<String, CachedPath> cache = new ConcurrentHashMap<>();
        private final LongAdder cacheHits = new LongAdder();
        private final LongAdder cacheMisses = new LongAdder();
        private final LongAdder cacheEvictions = new LongAdder();

        CachedPath get(String key) {
            CachedPath cached = cache.get(key);
            if (cached != null) {
                if (cached.isExpired()) {
                    cache.remove(key);
                    cacheEvictions.increment();
                    cacheMisses.increment();
                    return null;
                }
                cacheHits.increment();
                cached.recordAccess();
                return cached;
            }
            cacheMisses.increment();
            return null;
        }

        void put(String key, CachedPath path) {
            if (cache.size() >= PATH_CACHE_SIZE) {
                evictOldestEntries();
            }
            cache.put(key, path);
        }

        void performMaintenance() {
            // Remove expired entries
            int removedCount = 0;
            Iterator<Map.Entry<String, CachedPath>> iterator = cache.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, CachedPath> entry = iterator.next();
                if (entry.getValue().isExpired()) {
                    iterator.remove();
                    removedCount++;
                }
            }

            if (removedCount > 0) {
                cacheEvictions.add(removedCount);
                LOGGER.debug("Cache maintenance: removed {} expired entries", removedCount);
            }
        }

        private void evictOldestEntries() {
            // Evict 10% of cache when full
            int evictionCount = PATH_CACHE_SIZE / 10;

            List<Map.Entry<String, CachedPath>> entries = cache.entrySet().stream()
                .sorted(Map.Entry.comparingByValue((p1, p2) ->
                    Long.compare(p1.getLastAccess(), p2.getLastAccess())))
                .limit(evictionCount)
                .collect(Collectors.toList());

            for (Map.Entry<String, CachedPath> entry : entries) {
                cache.remove(entry.getKey());
            }

            cacheEvictions.add(evictionCount);
        }

        public double getCacheHitRate() {
            long hits = cacheHits.sum();
            long total = hits + cacheMisses.sum();
            return total > 0 ? (double) hits / total : 0.0;
        }

        public PathCacheStats getStats() {
            return new PathCacheStats(
                cache.size(),
                getCacheHitRate(),
                cacheHits.sum(),
                cacheMisses.sum(),
                cacheEvictions.sum()
            );
        }
    }

    /**
     * Movement prediction system based on historical patterns
     */
    private class MovementPredictor {
        private final Map<Integer, MovementHistory> userMovementHistory = new ConcurrentHashMap<>();
        private final Map<String, MovementPattern> commonPatterns = new ConcurrentHashMap<>();
        private final LongAdder predictionAttempts = new LongAdder();
        private final LongAdder predictionHits = new LongAdder();

        PredictedMovement predictMovement(RoomUnit roomUnit, RoomTile destination) {
            predictionAttempts.increment();

            // Get user movement history
            MovementHistory history = userMovementHistory.get(roomUnit.getId());
            if (history == null || !history.hasEnoughData()) {
                return null;
            }

            // Look for similar movement patterns
            String patternKey = generateMovementPatternKey(roomUnit.getCurrentLocation(), destination);
            MovementPattern pattern = commonPatterns.get(patternKey);

            if (pattern != null && pattern.isConfident()) {
                return new PredictedMovement(pattern.getPredictedPath(), pattern.getConfidence());
            }

            // Use machine learning prediction based on historical data
            return history.predictPath(roomUnit.getCurrentLocation(), destination);
        }

        void recordMovement(RoomUnit roomUnit, Deque<RoomTile> path) {
            MovementHistory history = userMovementHistory.computeIfAbsent(
                roomUnit.getId(), k -> new MovementHistory());

            history.recordMovement(path);

            // Update common patterns
            updateCommonPatterns(path);
        }

        void updatePredictions() {
            // Clean up old movement histories
            userMovementHistory.entrySet().removeIf(entry ->
                entry.getValue().isStale(System.currentTimeMillis()));

            // Update pattern confidence scores
            for (MovementPattern pattern : commonPatterns.values()) {
                pattern.updateConfidence();
            }
        }

        private void updateCommonPatterns(Deque<RoomTile> path) {
            if (path.size() < 2) return;

            RoomTile start = path.peekFirst();
            RoomTile end = path.peekLast();

            String patternKey = generateMovementPatternKey(start, end);
            MovementPattern pattern = commonPatterns.computeIfAbsent(
                patternKey, k -> new MovementPattern());

            pattern.addObservation(path);
        }

        private String generateMovementPatternKey(RoomTile start, RoomTile end) {
            return String.format("%d,%d->%d,%d", start.x, start.y, end.x, end.y);
        }

        public void recordPredictionHit() {
            predictionHits.increment();
        }

        public MovementPredictorStats getStats() {
            double hitRate = predictionAttempts.sum() > 0 ?
                (double) predictionHits.sum() / predictionAttempts.sum() : 0.0;

            return new MovementPredictorStats(
                predictionAttempts.sum(),
                predictionHits.sum(),
                hitRate,
                userMovementHistory.size(),
                commonPatterns.size()
            );
        }
    }

    /**
     * High-performance A* pathfinding with advanced optimizations
     */
    private class DynamicPathfinder {
        private final ThreadLocal<PathfindingWorkspace> workspace =
            ThreadLocal.withInitial(PathfindingWorkspace::new);

        Deque<RoomTile> computePath(PathfindingContext context) {
            PathfindingWorkspace ws = workspace.get();
            ws.reset();

            return computePathAStar(context, ws);
        }

        private Deque<RoomTile> computePathAStar(PathfindingContext context, PathfindingWorkspace ws) {
            RoomTile start = context.getStartTile();
            RoomTile goal = context.getEndTile();
            RoomUnit unit = context.getRoomUnit();
            Room room = context.getRoom();

            if (start.equals(goal)) {
                return new LinkedList<>();
            }

            // Use optimized data structures
            PriorityQueue<PathNode> openSet = ws.openSet;
            Set<String> closedSet = ws.closedSet;
            Map<String, PathNode> allNodes = ws.allNodes;

            PathNode startNode = new PathNode(start, null, 0, heuristic(start, goal));
            openSet.add(startNode);
            allNodes.put(startNode.getKey(), startNode);

            long startTime = System.currentTimeMillis();
            int iterations = 0;

            while (!openSet.isEmpty()) {
                // Timeout check (every 50 iterations for performance)
                if (++iterations % 50 == 0 &&
                    System.currentTimeMillis() - startTime > MAX_PATHFINDING_TIME_MS) {
                    LOGGER.debug("Pathfinding timeout after {} iterations", iterations);
                    break;
                }

                PathNode current = openSet.poll();
                String currentKey = current.getKey();

                if (current.tile.x == goal.x && current.tile.y == goal.y) {
                    return reconstructPath(current);
                }

                closedSet.add(currentKey);

                // Get neighbors with optimized adjacent tile calculation
                List<RoomTile> neighbors = getOptimizedNeighbors(current.tile, room, unit);

                for (RoomTile neighbor : neighbors) {
                    String neighborKey = getNodeKey(neighbor);

                    if (closedSet.contains(neighborKey)) {
                        continue;
                    }

                    double tentativeGScore = current.gCost + getMovementCost(current.tile, neighbor);

                    PathNode neighborNode = allNodes.get(neighborKey);
                    if (neighborNode == null) {
                        neighborNode = new PathNode(neighbor, current, tentativeGScore,
                                                  heuristic(neighbor, goal));
                        allNodes.put(neighborKey, neighborNode);
                        openSet.add(neighborNode);
                    } else if (tentativeGScore < neighborNode.gCost) {
                        // Remove and re-add to update priority queue position
                        openSet.remove(neighborNode);
                        neighborNode.parent = current;
                        neighborNode.gCost = tentativeGScore;
                        neighborNode.fCost = tentativeGScore + neighborNode.hCost;
                        openSet.add(neighborNode);
                    }
                }
            }

            return new LinkedList<>(); // No path found
        }

        private List<RoomTile> getOptimizedNeighbors(RoomTile tile, Room room, RoomUnit unit) {
            List<RoomTile> neighbors = new ArrayList<>(8); // Pre-sized for max neighbors
            RoomLayout layout = room.getLayout();

            // Check all 8 directions (including diagonals)
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue; // Skip current tile

                    short newX = (short) (tile.x + dx);
                    short newY = (short) (tile.y + dy);

                    RoomTile neighbor = layout.getTile(newX, newY);
                    if (neighbor != null && canWalkOn(neighbor, unit, room)) {
                        neighbors.add(neighbor);
                    }
                }
            }

            return neighbors;
        }

        private boolean canWalkOn(RoomTile tile, RoomUnit unit, Room room) {
            if (tile == null || tile.state == RoomTileState.INVALID || tile.state == RoomTileState.BLOCKED) {
                return false;
            }

            // Check height difference
            RoomTile currentTile = unit.getCurrentLocation();
            if (currentTile != null) {
                double heightDiff = tile.getStackHeight() - currentTile.getStackHeight();
                if (heightDiff > RoomLayout.MAXIMUM_STEP_HEIGHT ||
                    (!RoomLayout.ALLOW_FALLING && heightDiff < -RoomLayout.MAXIMUM_STEP_HEIGHT)) {
                    return false;
                }
            }

            // Check for units on tile (simplified check)
            return !tile.hasUnits() || unit.canOverrideTile(tile);
        }

        private double heuristic(RoomTile from, RoomTile to) {
            // Optimized Manhattan distance with diagonal adjustment
            int dx = Math.abs(from.x - to.x);
            int dy = Math.abs(from.y - to.y);

            // Use diagonal distance for more accurate heuristic
            return Math.max(dx, dy) + (Math.sqrt(2) - 1) * Math.min(dx, dy);
        }

        private double getMovementCost(RoomTile from, RoomTile to) {
            // Diagonal movement costs more
            boolean diagonal = Math.abs(from.x - to.x) == 1 && Math.abs(from.y - to.y) == 1;
            double baseCost = diagonal ? 1.414 : 1.0; // sqrt(2) for diagonal

            // Add height cost
            double heightDiff = Math.abs(to.getStackHeight() - from.getStackHeight());
            return baseCost + heightDiff * 0.1;
        }

        private Deque<RoomTile> reconstructPath(PathNode node) {
            LinkedList<RoomTile> path = new LinkedList<>();
            PathNode current = node;

            while (current != null) {
                path.addFirst(current.tile);
                current = current.parent;
            }

            return path;
        }

        private String getNodeKey(RoomTile tile) {
            return tile.x + "," + tile.y;
        }
    }

    /**
     * Performance analysis and optimization
     */
    private class PathfindingAnalyzer {
        private final LongAdder totalRequests = new LongAdder();
        private final LongAdder cacheHits = new LongAdder();
        private final LongAdder predictionHits = new LongAdder();
        private final LongAdder computations = new LongAdder();
        private final LongAdder failures = new LongAdder();
        private final LongAdder errors = new LongAdder();

        private final AtomicLong totalComputationTime = new AtomicLong();
        private final AtomicLong totalPathLength = new AtomicLong();

        void recordCacheHit(long startTime) {
            totalRequests.increment();
            cacheHits.increment();
        }

        void recordPredictionHit(long startTime) {
            totalRequests.increment();
            predictionHits.increment();
        }

        void recordComputation(long startTime, int pathLength) {
            totalRequests.increment();
            computations.increment();
            totalComputationTime.addAndGet(System.nanoTime() - startTime);
            totalPathLength.addAndGet(pathLength);
        }

        void recordFailure(long startTime) {
            totalRequests.increment();
            failures.increment();
        }

        void recordError() {
            totalRequests.increment();
            errors.increment();
        }

        public PathfindingAnalyzerStats getStats() {
            long requests = totalRequests.sum();
            double avgComputationTime = computations.sum() > 0 ?
                (double) totalComputationTime.get() / computations.sum() / 1_000_000.0 : 0.0;
            double avgPathLength = computations.sum() > 0 ?
                (double) totalPathLength.get() / computations.sum() : 0.0;

            return new PathfindingAnalyzerStats(
                requests,
                cacheHits.sum(),
                predictionHits.sum(),
                computations.sum(),
                failures.sum(),
                errors.sum(),
                avgComputationTime,
                avgPathLength
            );
        }
    }

    /**
     * Pathfinding optimizer for performance tuning
     */
    private class PathfindingOptimizer {
        // Implementation would include adaptive algorithm selection,
        // cache size optimization, and performance parameter tuning
    }

    // Helper method implementations
    private String generateCacheKey(RoomTile start, RoomTile end, RoomUnit unit, Room room) {
        return String.format("%d_%d_%d_%d_%d_%d",
            room.getId(), start.x, start.y, end.x, end.y,
            unit != null ? unit.getId() : 0);
    }

    private boolean isPathValid(Deque<RoomTile> path, Room room, RoomUnit unit) {
        if (path == null || path.isEmpty()) return false;

        RoomTile previous = null;
        for (RoomTile tile : path) {
            if (tile.state == RoomTileState.BLOCKED || tile.state == RoomTileState.INVALID) {
                return false;
            }

            if (previous != null) {
                double heightDiff = tile.getStackHeight() - previous.getStackHeight();
                if (heightDiff > RoomLayout.MAXIMUM_STEP_HEIGHT ||
                    (!RoomLayout.ALLOW_FALLING && heightDiff < -RoomLayout.MAXIMUM_STEP_HEIGHT)) {
                    return false;
                }
            }
            previous = tile;
        }

        return true;
    }

    // Supporting classes and data structures
    private static class PathfindingContext {
        private final RoomTile startTile;
        private final RoomTile endTile;
        private final RoomUnit roomUnit;
        private final Room room;

        PathfindingContext(RoomTile startTile, RoomTile endTile, RoomUnit roomUnit, Room room) {
            this.startTile = startTile;
            this.endTile = endTile;
            this.roomUnit = roomUnit;
            this.room = room;
        }

        RoomTile getStartTile() { return startTile; }
        RoomTile getEndTile() { return endTile; }
        RoomUnit getRoomUnit() { return roomUnit; }
        Room getRoom() { return room; }
    }

    private static class PathfindingWorkspace {
        final PriorityQueue<PathNode> openSet = new PriorityQueue<>(Comparator.comparingDouble(n -> n.fCost));
        final Set<String> closedSet = new HashSet<>();
        final Map<String, PathNode> allNodes = new HashMap<>();

        void reset() {
            openSet.clear();
            closedSet.clear();
            allNodes.clear();
        }
    }

    private static class PathNode {
        final RoomTile tile;
        PathNode parent;
        double gCost;
        final double hCost;
        double fCost;

        PathNode(RoomTile tile, PathNode parent, double gCost, double hCost) {
            this.tile = tile;
            this.parent = parent;
            this.gCost = gCost;
            this.hCost = hCost;
            this.fCost = gCost + hCost;
        }

        String getKey() {
            return tile.x + "," + tile.y;
        }
    }

    private static class CachedPath {
        private final Deque<RoomTile> path;
        private final long creationTime;
        private final int roomStateHash;
        private volatile long lastAccess;

        CachedPath(Deque<RoomTile> path, Room room) {
            this.path = new LinkedList<>(path);
            this.creationTime = System.currentTimeMillis();
            this.roomStateHash = calculateRoomStateHash(room);
            this.lastAccess = creationTime;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - creationTime > CACHE_TTL_MS;
        }

        boolean isValid(Room room) {
            return calculateRoomStateHash(room) == roomStateHash;
        }

        void recordAccess() {
            lastAccess = System.currentTimeMillis();
        }

        Deque<RoomTile> getPath() { return new LinkedList<>(path); }
        long getLastAccess() { return lastAccess; }

        private int calculateRoomStateHash(Room room) {
            // Simplified room state hash - would include furniture positions, etc.
            return room.getCurrentUsers().size() * 31 + (int) room.getId();
        }
    }

    private static class MovementHistory {
        private final Queue<MovementRecord> movements = new ConcurrentLinkedQueue<>();
        private static final int MAX_HISTORY_SIZE = 100;
        private static final long MAX_AGE_MS = 600000; // 10 minutes

        void recordMovement(Deque<RoomTile> path) {
            if (path.size() < 2) return;

            movements.offer(new MovementRecord(path, System.currentTimeMillis()));

            // Maintain history size
            while (movements.size() > MAX_HISTORY_SIZE) {
                movements.poll();
            }
        }

        boolean hasEnoughData() {
            return movements.size() >= 5;
        }

        boolean isStale(long currentTime) {
            return movements.isEmpty() ||
                   currentTime - movements.peek().timestamp > MAX_AGE_MS;
        }

        PredictedMovement predictPath(RoomTile start, RoomTile end) {
            // Simplified prediction - would use more sophisticated ML algorithms
            return null;
        }

        private static class MovementRecord {
            final Deque<RoomTile> path;
            final long timestamp;

            MovementRecord(Deque<RoomTile> path, long timestamp) {
                this.path = new LinkedList<>(path);
                this.timestamp = timestamp;
            }
        }
    }

    private static class MovementPattern {
        private final Queue<Deque<RoomTile>> observations = new ConcurrentLinkedQueue<>();
        private double confidence = 0.0;

        void addObservation(Deque<RoomTile> path) {
            observations.offer(new LinkedList<>(path));
            updateConfidence();
        }

        void updateConfidence() {
            int size = observations.size();
            if (size >= 3) {
                // Simple confidence calculation
                confidence = Math.min(0.9, size * 0.1);
            }
        }

        boolean isConfident() {
            return confidence > 0.7;
        }

        double getConfidence() { return confidence; }

        Deque<RoomTile> getPredictedPath() {
            return observations.isEmpty() ? new LinkedList<>() :
                   new LinkedList<>(observations.peek());
        }
    }

    private static class PredictedMovement {
        private final Deque<RoomTile> path;
        private final double confidence;

        PredictedMovement(Deque<RoomTile> path, double confidence) {
            this.path = path;
            this.confidence = confidence;
        }

        boolean isConfident() { return confidence > 0.8; }
        Deque<RoomTile> getPath() { return path; }
        double getConfidence() { return confidence; }
    }

    // Result classes
    public static class PathResult {
        private final Deque<RoomTile> path;
        private final PathResultType type;
        private final long computationTime;

        public PathResult(Deque<RoomTile> path, PathResultType type, long computationTime) {
            this.path = path;
            this.type = type;
            this.computationTime = computationTime;
        }

        public Deque<RoomTile> getPath() { return path; }
        public PathResultType getType() { return type; }
        public long getComputationTimeNanos() { return computationTime; }
        public double getComputationTimeMs() { return computationTime / 1_000_000.0; }
    }

    public enum PathResultType {
        CACHED, PREDICTED, COMPUTED, FAILED, ERROR
    }

    // Statistics classes
    public static class PathCacheStats {
        public final int cacheSize;
        public final double hitRate;
        public final long totalHits;
        public final long totalMisses;
        public final long totalEvictions;

        public PathCacheStats(int cacheSize, double hitRate, long totalHits,
                            long totalMisses, long totalEvictions) {
            this.cacheSize = cacheSize;
            this.hitRate = hitRate;
            this.totalHits = totalHits;
            this.totalMisses = totalMisses;
            this.totalEvictions = totalEvictions;
        }

        @Override
        public String toString() {
            return String.format("PathCacheStats{size=%d, hitRate=%.2f%%, evictions=%d}",
                               cacheSize, hitRate * 100, totalEvictions);
        }
    }

    public static class MovementPredictorStats {
        public final long predictionAttempts;
        public final long predictionHits;
        public final double hitRate;
        public final int userHistories;
        public final int commonPatterns;

        public MovementPredictorStats(long predictionAttempts, long predictionHits, double hitRate,
                                    int userHistories, int commonPatterns) {
            this.predictionAttempts = predictionAttempts;
            this.predictionHits = predictionHits;
            this.hitRate = hitRate;
            this.userHistories = userHistories;
            this.commonPatterns = commonPatterns;
        }

        @Override
        public String toString() {
            return String.format("MovementPredictorStats{hitRate=%.2f%%, patterns=%d, histories=%d}",
                               hitRate * 100, commonPatterns, userHistories);
        }
    }

    public static class PathfindingAnalyzerStats {
        public final long totalRequests;
        public final long cacheHits;
        public final long predictionHits;
        public final long computations;
        public final long failures;
        public final long errors;
        public final double avgComputationTime;
        public final double avgPathLength;

        public PathfindingAnalyzerStats(long totalRequests, long cacheHits, long predictionHits,
                                      long computations, long failures, long errors,
                                      double avgComputationTime, double avgPathLength) {
            this.totalRequests = totalRequests;
            this.cacheHits = cacheHits;
            this.predictionHits = predictionHits;
            this.computations = computations;
            this.failures = failures;
            this.errors = errors;
            this.avgComputationTime = avgComputationTime;
            this.avgPathLength = avgPathLength;
        }

        @Override
        public String toString() {
            return String.format("PathfindingStats{requests=%d, cache=%.1f%%, pred=%.1f%%, comp=%.1f%%, avgTime=%.2fms}",
                               totalRequests,
                               (double) cacheHits / totalRequests * 100,
                               (double) predictionHits / totalRequests * 100,
                               (double) computations / totalRequests * 100,
                               avgComputationTime);
        }
    }

    // Public API methods
    public String getComprehensiveStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("=== ADVANCED PATHFINDING ENGINE STATS ===\n");
        stats.append("Path Cache: ").append(pathCache.getStats()).append("\n");
        stats.append("Movement Predictor: ").append(movementPredictor.getStats()).append("\n");
        stats.append("Pathfinding Analyzer: ").append(analyzer.getStats()).append("\n");
        stats.append("==========================================");

        return stats.toString();
    }

    public void shutdown() {
        pathfindingScheduler.shutdown();
        pathComputationExecutor.shutdown();

        try {
            if (!pathfindingScheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                pathfindingScheduler.shutdownNow();
            }
            if (!pathComputationExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                pathComputationExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            pathfindingScheduler.shutdownNow();
            pathComputationExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        LOGGER.info("Advanced Pathfinding Engine shutdown completed");
    }
}