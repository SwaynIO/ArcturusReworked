package com.eu.habbo.core.pathfinding;

import com.eu.habbo.Emulator;
import com.eu.habbo.habbohotel.rooms.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Pathfinding Integration Layer
 * Provides seamless integration between the legacy pathfinding system
 * and the new Advanced Pathfinding Engine
 */
public class PathfindingIntegration {
    private static final Logger LOGGER = LoggerFactory.getLogger(PathfindingIntegration.class);

    private static final boolean ADVANCED_PATHFINDING_ENABLED = true;
    private static final long PATHFINDING_TIMEOUT_MS = 100;

    /**
     * Enhanced pathfinding method that replaces the original RoomLayout.findPath
     * Provides backward compatibility while leveraging advanced optimizations
     */
    public static Deque<RoomTile> findPath(RoomLayout layout, RoomTile oldTile, RoomTile newTile,
                                          RoomTile goalLocation, RoomUnit roomUnit, boolean isWalktroughRetry) {

        // Validate inputs
        if (layout == null || oldTile == null || newTile == null || roomUnit == null) {
            return new LinkedList<>();
        }

        Room room = layout.getRoom();
        if (room == null || !room.isLoaded()) {
            return new LinkedList<>();
        }

        // Check if advanced pathfinding is enabled and conditions are met
        if (ADVANCED_PATHFINDING_ENABLED && shouldUseAdvancedPathfinding(oldTile, newTile, room)) {
            try {
                return findPathAdvanced(oldTile, newTile, roomUnit, room);
            } catch (Exception e) {
                LOGGER.warn("Advanced pathfinding failed, falling back to legacy: {}", e.getMessage());
                return findPathLegacy(layout, oldTile, newTile, goalLocation, roomUnit, isWalktroughRetry);
            }
        } else {
            // Use legacy pathfinding for simple cases or when advanced is disabled
            return findPathLegacy(layout, oldTile, newTile, goalLocation, roomUnit, isWalktroughRetry);
        }
    }

    /**
     * Advanced pathfinding using the new engine
     */
    private static Deque<RoomTile> findPathAdvanced(RoomTile startTile, RoomTile endTile,
                                                   RoomUnit roomUnit, Room room) {
        AdvancedPathfindingEngine engine = AdvancedPathfindingEngine.getInstance();

        try {
            CompletableFuture<AdvancedPathfindingEngine.PathResult> future =
                engine.findOptimalPath(startTile, endTile, roomUnit, room);

            AdvancedPathfindingEngine.PathResult result =
                future.get(PATHFINDING_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            if (result != null && result.getPath() != null && !result.getPath().isEmpty()) {
                logPathfindingResult(result, startTile, endTile);
                return result.getPath();
            }

        } catch (Exception e) {
            LOGGER.debug("Advanced pathfinding timeout or error: {}", e.getMessage());
        }

        return new LinkedList<>();
    }

    /**
     * Legacy pathfinding using the existing optimized algorithm
     */
    private static Deque<RoomTile> findPathLegacy(RoomLayout layout, RoomTile oldTile, RoomTile newTile,
                                                 RoomTile goalLocation, RoomUnit roomUnit, boolean isWalktroughRetry) {
        // Use the existing optimized pathfinding from RoomLayout
        return layout.findPathOriginal(oldTile, newTile, goalLocation, roomUnit, isWalktroughRetry);
    }

    /**
     * Determine whether to use advanced pathfinding based on various factors
     */
    private static boolean shouldUseAdvancedPathfinding(RoomTile start, RoomTile end, Room room) {
        // Distance-based decision
        double distance = calculateDistance(start, end);

        // Use advanced pathfinding for:
        // 1. Medium to long distance paths (> 3 tiles)
        // 2. Rooms with many users (potential for collision avoidance)
        // 3. Rooms with complex layouts

        boolean longDistance = distance > 3.0;
        boolean busyRoom = room.getCurrentUsers().size() > 5;
        boolean complexRoom = room.getRoomInfo().getName().toLowerCase().contains("maze") ||
                             room.getRoomInfo().getName().toLowerCase().contains("game");

        return longDistance || busyRoom || complexRoom;
    }

    private static double calculateDistance(RoomTile start, RoomTile end) {
        int dx = Math.abs(start.x - end.x);
        int dy = Math.abs(start.y - end.y);
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static void logPathfindingResult(AdvancedPathfindingEngine.PathResult result,
                                           RoomTile start, RoomTile end) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Advanced pathfinding: {} -> {} | Type: {} | Time: {:.2f}ms | Length: {}",
                        start.x + "," + start.y,
                        end.x + "," + end.y,
                        result.getType(),
                        result.getComputationTimeMs(),
                        result.getPath().size());
        }
    }

    /**
     * Utility method for rooms to enable/disable advanced pathfinding per room
     */
    public static boolean isAdvancedPathfindingEnabledForRoom(Room room) {
        if (!ADVANCED_PATHFINDING_ENABLED) {
            return false;
        }

        // Room-specific configuration could be added here
        // For example, certain room types might always use legacy pathfinding
        String roomName = room.getRoomInfo().getName().toLowerCase();

        // Disable for certain room types that might not benefit
        if (roomName.contains("cafe") || roomName.contains("simple")) {
            return false;
        }

        return true;
    }

    /**
     * Performance monitoring for pathfinding decisions
     */
    public static class PathfindingMetrics {
        private static long advancedPathfindingCalls = 0;
        private static long legacyPathfindingCalls = 0;
        private static long totalAdvancedTime = 0;
        private static long totalLegacyTime = 0;

        public static void recordAdvancedCall(long timeMs) {
            advancedPathfindingCalls++;
            totalAdvancedTime += timeMs;
        }

        public static void recordLegacyCall(long timeMs) {
            legacyPathfindingCalls++;
            totalLegacyTime += timeMs;
        }

        public static String getStats() {
            long totalCalls = advancedPathfindingCalls + legacyPathfindingCalls;
            if (totalCalls == 0) return "No pathfinding calls recorded";

            double advancedPercentage = (double) advancedPathfindingCalls / totalCalls * 100;
            double avgAdvancedTime = advancedPathfindingCalls > 0 ?
                (double) totalAdvancedTime / advancedPathfindingCalls : 0;
            double avgLegacyTime = legacyPathfindingCalls > 0 ?
                (double) totalLegacyTime / legacyPathfindingCalls : 0;

            return String.format("Pathfinding Stats: Advanced: %.1f%% (%d calls, %.2fms avg) | Legacy: %.1f%% (%d calls, %.2fms avg)",
                               advancedPercentage, advancedPathfindingCalls, avgAdvancedTime,
                               100 - advancedPercentage, legacyPathfindingCalls, avgLegacyTime);
        }

        public static void reset() {
            advancedPathfindingCalls = 0;
            legacyPathfindingCalls = 0;
            totalAdvancedTime = 0;
            totalLegacyTime = 0;
        }
    }

    /**
     * Helper method for batch pathfinding optimization
     * Useful for NPCs, bots, or multiple simultaneous pathfinding requests
     */
    public static Map<RoomUnit, Deque<RoomTile>> findMultiplePaths(Room room,
                                                                  Map<RoomUnit, RoomTile> destinations) {
        Map<RoomUnit, Deque<RoomTile>> results = new HashMap<>();

        if (ADVANCED_PATHFINDING_ENABLED && destinations.size() > 3) {
            // Use advanced pathfinding for batch processing
            AdvancedPathfindingEngine engine = AdvancedPathfindingEngine.getInstance();

            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (Map.Entry<RoomUnit, RoomTile> entry : destinations.entrySet()) {
                RoomUnit unit = entry.getKey();
                RoomTile destination = entry.getValue();

                CompletableFuture<Void> future = engine.findOptimalPath(
                    unit.getCurrentLocation(), destination, unit, room
                ).thenAccept(result -> {
                    if (result != null && result.getPath() != null) {
                        results.put(unit, result.getPath());
                    } else {
                        results.put(unit, new LinkedList<>());
                    }
                });

                futures.add(future);
            }

            // Wait for all pathfinding to complete (with timeout)
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(PATHFINDING_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                LOGGER.warn("Batch pathfinding timeout or error: {}", e.getMessage());
            }
        } else {
            // Fallback to individual pathfinding
            for (Map.Entry<RoomUnit, RoomTile> entry : destinations.entrySet()) {
                RoomUnit unit = entry.getKey();
                RoomTile destination = entry.getValue();

                Deque<RoomTile> path = findPath(room.getLayout(),
                    unit.getCurrentLocation(), destination, destination, unit, false);
                results.put(unit, path);
            }
        }

        return results;
    }

    /**
     * Pathfinding precomputation for frequently accessed destinations
     * Useful for teleporters, furniture, or common destinations
     */
    public static void precomputePaths(Room room, List<RoomTile> commonDestinations) {
        if (!ADVANCED_PATHFINDING_ENABLED || commonDestinations == null || commonDestinations.isEmpty()) {
            return;
        }

        AdvancedPathfindingEngine engine = AdvancedPathfindingEngine.getInstance();

        // Create a dummy room unit for precomputation
        RoomUnit dummyUnit = new RoomUnit();
        dummyUnit.setRoom(room);

        // Precompute paths from door to all common destinations
        RoomTile doorTile = room.getLayout().getDoorTile();
        if (doorTile != null) {
            for (RoomTile destination : commonDestinations) {
                engine.findOptimalPath(doorTile, destination, dummyUnit, room);
            }
        }

        LOGGER.debug("Precomputed {} paths for room {}", commonDestinations.size(), room.getId());
    }

    /**
     * Dynamic pathfinding difficulty adjustment
     * Adjusts pathfinding algorithm based on room complexity and server load
     */
    public static PathfindingDifficulty assessPathfindingDifficulty(Room room) {
        int roomSize = room.getLayout().getMapSizeX() * room.getLayout().getMapSizeY();
        int userCount = room.getCurrentUsers().size();
        int furnitureCount = room.getRoomItems().size();

        // Simple scoring system
        int complexityScore = 0;

        if (roomSize > 400) complexityScore += 2;       // Large room
        if (userCount > 20) complexityScore += 2;       // Busy room
        if (furnitureCount > 100) complexityScore += 2; // Furniture-heavy room

        // Check for game rooms or mazes
        String roomName = room.getRoomInfo().getName().toLowerCase();
        if (roomName.contains("maze") || roomName.contains("game") || roomName.contains("obstacle")) {
            complexityScore += 3;
        }

        if (complexityScore >= 5) return PathfindingDifficulty.VERY_HIGH;
        if (complexityScore >= 3) return PathfindingDifficulty.HIGH;
        if (complexityScore >= 2) return PathfindingDifficulty.MEDIUM;
        return PathfindingDifficulty.LOW;
    }

    public enum PathfindingDifficulty {
        LOW,      // Simple pathfinding, prefer speed
        MEDIUM,   // Balanced approach
        HIGH,     // Advanced pathfinding with optimizations
        VERY_HIGH // Full advanced pathfinding with all features
    }
}