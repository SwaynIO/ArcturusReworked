package com.eu.habbo.core.pathfinding;

import com.eu.habbo.habbohotel.pets.Pet;
import com.eu.habbo.habbohotel.rooms.*;
import com.eu.habbo.habbohotel.users.Habbo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Intelligent Pet Pathfinding System
 * Advanced AI-driven pathfinding specifically designed for pets with
 * natural behavior patterns, owner following, and environmental awareness
 */
public class PetIntelligentPathfinding {
    private static final Logger LOGGER = LoggerFactory.getLogger(PetIntelligentPathfinding.class);

    private static PetIntelligentPathfinding instance;

    // Core AI components
    private final PetBehaviorAnalyzer behaviorAnalyzer;
    private final OwnerFollowingSystem ownerFollowing;
    private final EnvironmentAwareness environmentAI;
    private final PetEmotionalState emotionalSystem;
    private final SocialInteractionAI socialAI;

    // Pathfinding optimizations
    private final PetMovementPredictor movementPredictor;
    private final PetPathCache petPathCache;

    // Execution
    private final ScheduledExecutorService petAIScheduler;
    private final ExecutorService petPathfindingExecutor;

    // Configuration
    private static final double OWNER_FOLLOW_PROBABILITY = 0.7;
    private static final double EXPLORATION_PROBABILITY = 0.3;
    private static final int PET_PATHFINDING_TIMEOUT_MS = 25;
    private static final double NATURAL_MOVEMENT_RANDOMNESS = 0.15;

    private PetIntelligentPathfinding() {
        this.behaviorAnalyzer = new PetBehaviorAnalyzer();
        this.ownerFollowing = new OwnerFollowingSystem();
        this.environmentAI = new EnvironmentAwareness();
        this.emotionalSystem = new PetEmotionalState();
        this.socialAI = new SocialInteractionAI();
        this.movementPredictor = new PetMovementPredictor();
        this.petPathCache = new PetPathCache();

        this.petAIScheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "PetAI-Scheduler");
            t.setDaemon(true);
            return t;
        });

        this.petPathfindingExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "PetPathfinding");
            t.setDaemon(true);
            return t;
        });

        initializePetAI();
        LOGGER.info("Pet Intelligent Pathfinding System initialized");
    }

    public static synchronized PetIntelligentPathfinding getInstance() {
        if (instance == null) {
            instance = new PetIntelligentPathfinding();
        }
        return instance;
    }

    private void initializePetAI() {
        // Start behavior analysis updates
        petAIScheduler.scheduleWithFixedDelay(
            behaviorAnalyzer::updateBehaviorPatterns,
            30000, 30000, TimeUnit.MILLISECONDS // 30 seconds
        );

        // Start emotional state updates
        petAIScheduler.scheduleWithFixedDelay(
            emotionalSystem::updateEmotionalStates,
            15000, 15000, TimeUnit.MILLISECONDS // 15 seconds
        );
    }

    /**
     * Intelligent pet pathfinding with AI-driven destination selection
     */
    public CompletableFuture<PetPathResult> findPetPath(Pet pet, Room room) {
        return CompletableFuture.supplyAsync(() -> {
            long startTime = System.nanoTime();

            try {
                RoomUnit petUnit = pet.getRoomUnit();
                if (petUnit == null || petUnit.getCurrentLocation() == null) {
                    return new PetPathResult(new LinkedList<>(), PetPathType.FAILED, 0);
                }

                // Analyze pet's current emotional and behavioral state
                PetState currentState = analyzePetState(pet, room);

                // Determine destination using AI
                RoomTile destination = selectIntelligentDestination(pet, room, currentState);

                if (destination == null) {
                    return new PetPathResult(new LinkedList<>(), PetPathType.NO_DESTINATION, 0);
                }

                // Check cache first
                String cacheKey = generatePetCacheKey(pet, petUnit.getCurrentLocation(), destination);
                Deque<RoomTile> cachedPath = petPathCache.get(cacheKey);
                if (cachedPath != null) {
                    return new PetPathResult(cachedPath, PetPathType.CACHED,
                                           System.nanoTime() - startTime);
                }

                // Compute path with pet-specific considerations
                Deque<RoomTile> path = computePetPath(petUnit.getCurrentLocation(), destination,
                                                    petUnit, room, currentState);

                if (path != null && !path.isEmpty()) {
                    // Add natural movement variations
                    path = addNaturalMovementVariations(path, pet, currentState);

                    // Cache the path
                    petPathCache.put(cacheKey, path);

                    // Update movement predictor
                    movementPredictor.recordPetMovement(pet, path);

                    PetPathType pathType = determinePathType(currentState);
                    return new PetPathResult(path, pathType, System.nanoTime() - startTime);
                }

                return new PetPathResult(new LinkedList<>(), PetPathType.FAILED,
                                       System.nanoTime() - startTime);

            } catch (Exception e) {
                LOGGER.error("Pet pathfinding failed for pet {}", pet.getId(), e);
                return new PetPathResult(new LinkedList<>(), PetPathType.ERROR, 0);
            }
        }, petPathfindingExecutor);
    }

    /**
     * Pet behavior analysis for intelligent movement decisions
     */
    private class PetBehaviorAnalyzer {
        private final Map<Integer, PetBehaviorProfile> petProfiles = new ConcurrentHashMap<>();
        private final LongAdder behaviorAnalyses = new LongAdder();

        void updateBehaviorPatterns() {
            // Update behavior patterns for all active pets
            for (PetBehaviorProfile profile : petProfiles.values()) {
                profile.updatePattern();
            }
        }

        PetBehaviorProfile getOrCreateProfile(Pet pet) {
            return petProfiles.computeIfAbsent(pet.getId(), k -> new PetBehaviorProfile(pet));
        }

        void recordBehavior(Pet pet, PetBehaviorType behavior, RoomTile location) {
            PetBehaviorProfile profile = getOrCreateProfile(pet);
            profile.recordBehavior(behavior, location);
            behaviorAnalyses.increment();
        }

        public BehaviorAnalyzerStats getStats() {
            return new BehaviorAnalyzerStats(behaviorAnalyses.sum(), petProfiles.size());
        }
    }

    /**
     * Owner following system with intelligent distance management
     */
    private class OwnerFollowingSystem {
        private final Map<Integer, OwnerRelationship> relationships = new ConcurrentHashMap<>();

        RoomTile calculateOptimalFollowingPosition(Pet pet, Room room) {
            Habbo owner = room.getHabbo(pet.getUserId());
            if (owner == null || owner.getRoomUnit() == null) {
                return null;
            }

            RoomTile ownerLocation = owner.getRoomUnit().getCurrentLocation();
            if (ownerLocation == null) {
                return null;
            }

            OwnerRelationship relationship = relationships.computeIfAbsent(pet.getId(),
                k -> new OwnerRelationship(pet));

            // Calculate optimal following distance based on pet type and relationship
            int optimalDistance = relationship.getOptimalFollowDistance();

            // Find tiles at optimal distance from owner
            List<RoomTile> candidateTiles = findTilesAtDistance(ownerLocation, optimalDistance, room);

            if (candidateTiles.isEmpty()) {
                return null;
            }

            // Select best tile considering pet preferences and room layout
            return selectBestFollowingTile(candidateTiles, pet, ownerLocation, room);
        }

        private List<RoomTile> findTilesAtDistance(RoomTile center, int distance, Room room) {
            List<RoomTile> tiles = new ArrayList<>();
            RoomLayout layout = room.getLayout();

            for (int dx = -distance; dx <= distance; dx++) {
                for (int dy = -distance; dy <= distance; dy++) {
                    int actualDistance = Math.max(Math.abs(dx), Math.abs(dy));
                    if (actualDistance == distance) {
                        short x = (short) (center.x + dx);
                        short y = (short) (center.y + dy);
                        RoomTile tile = layout.getTile(x, y);

                        if (tile != null && tile.state == RoomTileState.OPEN && !tile.hasUnits()) {
                            tiles.add(tile);
                        }
                    }
                }
            }

            return tiles;
        }

        private RoomTile selectBestFollowingTile(List<RoomTile> candidates, Pet pet,
                                               RoomTile ownerLocation, Room room) {
            if (candidates.isEmpty()) return null;

            // Score each candidate tile
            return candidates.stream()
                .max(Comparator.comparingDouble(tile ->
                    calculateFollowingTileScore(tile, pet, ownerLocation, room)))
                .orElse(null);
        }

        private double calculateFollowingTileScore(RoomTile tile, Pet pet, RoomTile ownerLocation, Room room) {
            double score = 0;

            // Prefer tiles that give good view of owner
            double viewScore = 1.0 / (1.0 + calculateObstacles(tile, ownerLocation, room));
            score += viewScore * 0.4;

            // Consider pet comfort (away from doors, crowds)
            double comfortScore = calculateComfortScore(tile, room);
            score += comfortScore * 0.3;

            // Factor in pet personality
            PetBehaviorProfile profile = behaviorAnalyzer.getOrCreateProfile(pet);
            double personalityScore = profile.getLocationPreferenceScore(tile);
            score += personalityScore * 0.3;

            return score;
        }

        private double calculateObstacles(RoomTile from, RoomTile to, Room room) {
            // Simplified line-of-sight calculation
            int dx = Math.abs(from.x - to.x);
            int dy = Math.abs(from.y - to.y);
            return Math.max(dx, dy) * 0.1; // Simple distance-based obstacle estimation
        }

        private double calculateComfortScore(RoomTile tile, Room room) {
            double score = 1.0;

            // Penalize tiles near door
            RoomTile door = room.getLayout().getDoorTile();
            if (door != null) {
                double doorDistance = Math.max(Math.abs(tile.x - door.x), Math.abs(tile.y - door.y));
                score *= Math.min(1.0, doorDistance / 3.0); // Comfort increases with distance from door
            }

            // Penalize crowded areas
            int nearbyUsers = 0;
            for (Habbo user : room.getHabbos()) {
                if (user.getRoomUnit() != null && user.getRoomUnit().getCurrentLocation() != null) {
                    RoomTile userTile = user.getRoomUnit().getCurrentLocation();
                    double distance = Math.max(Math.abs(tile.x - userTile.x), Math.abs(tile.y - userTile.y));
                    if (distance <= 2) nearbyUsers++;
                }
            }

            score *= Math.max(0.1, 1.0 - nearbyUsers * 0.2);
            return score;
        }
    }

    /**
     * Environmental awareness for natural pet behavior
     */
    private class EnvironmentAwareness {
        List<RoomTile> findInterestingLocations(Pet pet, Room room) {
            List<RoomTile> interesting = new ArrayList<>();

            // Find furniture that might interest pets
            room.getRoomItems().forEach(item -> {
                if (isPetInteresting(item.getBaseItem().getName(), pet)) {
                    interesting.add(room.getLayout().getTile(item.getX(), item.getY()));
                }
            });

            // Add corners and secluded spots for shy pets
            if (behaviorAnalyzer.getOrCreateProfile(pet).isShyPersonality()) {
                interesting.addAll(findQuietSpots(room));
            }

            // Add open spaces for active pets
            if (behaviorAnalyzer.getOrCreateProfile(pet).isActivePersonality()) {
                interesting.addAll(findOpenSpaces(room));
            }

            return interesting.stream()
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        }

        private boolean isPetInteresting(String itemName, Pet pet) {
            String name = itemName.toLowerCase();

            // General pet-interesting items
            if (name.contains("food") || name.contains("toy") || name.contains("bed") ||
                name.contains("water") || name.contains("treat")) {
                return true;
            }

            // Pet-type specific interests
            switch (pet.getType()) {
                case 0: // Dog
                    return name.contains("ball") || name.contains("bone") || name.contains("fetch");
                case 1: // Cat
                    return name.contains("mouse") || name.contains("yarn") || name.contains("scratch");
                case 2: // Crocodile
                    return name.contains("swamp") || name.contains("rock");
                default:
                    return false;
            }
        }

        private List<RoomTile> findQuietSpots(Room room) {
            List<RoomTile> quietSpots = new ArrayList<>();
            RoomLayout layout = room.getLayout();

            // Find corners
            for (short x = 0; x < layout.getMapSizeX(); x++) {
                for (short y = 0; y < layout.getMapSizeY(); y++) {
                    RoomTile tile = layout.getTile(x, y);
                    if (tile != null && tile.state == RoomTileState.OPEN) {
                        if (isCorner(x, y, layout)) {
                            quietSpots.add(tile);
                        }
                    }
                }
            }

            return quietSpots;
        }

        private List<RoomTile> findOpenSpaces(Room room) {
            List<RoomTile> openSpaces = new ArrayList<>();
            RoomLayout layout = room.getLayout();

            for (short x = 1; x < layout.getMapSizeX() - 1; x++) {
                for (short y = 1; y < layout.getMapSizeY() - 1; y++) {
                    RoomTile tile = layout.getTile(x, y);
                    if (tile != null && tile.state == RoomTileState.OPEN && !tile.hasUnits()) {
                        if (isOpenArea(x, y, layout)) {
                            openSpaces.add(tile);
                        }
                    }
                }
            }

            return openSpaces;
        }

        private boolean isCorner(short x, short y, RoomLayout layout) {
            int walls = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;
                    RoomTile adjacent = layout.getTile((short) (x + dx), (short) (y + dy));
                    if (adjacent == null || adjacent.state == RoomTileState.INVALID) {
                        walls++;
                    }
                }
            }
            return walls >= 3;
        }

        private boolean isOpenArea(short x, short y, RoomLayout layout) {
            int openTiles = 0;
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    RoomTile tile = layout.getTile((short) (x + dx), (short) (y + dy));
                    if (tile != null && tile.state == RoomTileState.OPEN) {
                        openTiles++;
                    }
                }
            }
            return openTiles >= 15; // 5x5 area mostly open
        }
    }

    /**
     * Pet emotional state system affecting movement patterns
     */
    private class PetEmotionalState {
        private final Map<Integer, PetEmotion> emotionalStates = new ConcurrentHashMap<>();

        void updateEmotionalStates() {
            for (PetEmotion emotion : emotionalStates.values()) {
                emotion.update();
            }
        }

        PetEmotion getOrCreateEmotion(Pet pet) {
            return emotionalStates.computeIfAbsent(pet.getId(), k -> new PetEmotion(pet));
        }

        void recordInteraction(Pet pet, InteractionType interaction) {
            PetEmotion emotion = getOrCreateEmotion(pet);
            emotion.recordInteraction(interaction);
        }
    }

    // Helper methods and classes
    private PetState analyzePetState(Pet pet, Room room) {
        PetBehaviorProfile behavior = behaviorAnalyzer.getOrCreateProfile(pet);
        PetEmotion emotion = emotionalSystem.getOrCreateEmotion(pet);

        return new PetState(
            behavior.getPrimaryBehavior(),
            emotion.getCurrentMood(),
            pet.getEnergy(),
            pet.getHappiness(),
            System.currentTimeMillis() - pet.getRoomUnit().getLastMovement()
        );
    }

    private RoomTile selectIntelligentDestination(Pet pet, Room room, PetState state) {
        Random random = new Random();

        // Decision tree based on pet state and behavior
        if (state.happiness < 50 && random.nextDouble() < OWNER_FOLLOW_PROBABILITY) {
            // Unhappy pets prefer to stay close to owner
            return ownerFollowing.calculateOptimalFollowingPosition(pet, room);
        }

        if (state.energy > 80 && random.nextDouble() < EXPLORATION_PROBABILITY) {
            // High energy pets explore
            List<RoomTile> interesting = environmentAI.findInterestingLocations(pet, room);
            if (!interesting.isEmpty()) {
                return interesting.get(random.nextInt(interesting.size()));
            }
        }

        // Default behavior based on personality
        PetBehaviorProfile profile = behaviorAnalyzer.getOrCreateProfile(pet);
        if (profile.isActivePersonality()) {
            List<RoomTile> openSpaces = environmentAI.findOpenSpaces(room);
            if (!openSpaces.isEmpty()) {
                return openSpaces.get(random.nextInt(openSpaces.size()));
            }
        }

        // Fallback: follow owner or random movement
        RoomTile ownerFollow = ownerFollowing.calculateOptimalFollowingPosition(pet, room);
        return ownerFollow != null ? ownerFollow : getRandomNearbyTile(pet.getRoomUnit().getCurrentLocation(), room);
    }

    private Deque<RoomTile> computePetPath(RoomTile start, RoomTile end, RoomUnit petUnit,
                                         Room room, PetState state) {
        // Use advanced pathfinding with pet-specific modifications
        try {
            CompletableFuture<AdvancedPathfindingEngine.PathResult> future =
                AdvancedPathfindingEngine.getInstance().findOptimalPath(start, end, petUnit, room);

            AdvancedPathfindingEngine.PathResult result =
                future.get(PET_PATHFINDING_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            return result != null ? result.getPath() : null;

        } catch (Exception e) {
            // Fallback to simple pathfinding
            return room.getLayout().findPath(start, end, end, petUnit);
        }
    }

    private Deque<RoomTile> addNaturalMovementVariations(Deque<RoomTile> path, Pet pet, PetState state) {
        if (path.size() < 3) return path;

        // Add slight randomness to make movement more natural
        Random random = new Random();
        LinkedList<RoomTile> naturalPath = new LinkedList<>();

        RoomTile previous = null;
        for (RoomTile tile : path) {
            naturalPath.add(tile);

            // Occasionally add a small pause or hesitation
            if (previous != null && random.nextDouble() < NATURAL_MOVEMENT_RANDOMNESS) {
                // Pet might hesitate or take a slightly different route
                if (state.getCurrentBehavior() == PetBehaviorType.CURIOUS) {
                    // Curious pets might investigate nearby tiles briefly
                    // This could be implemented as a small detour
                }
            }
            previous = tile;
        }

        return naturalPath;
    }

    private RoomTile getRandomNearbyTile(RoomTile center, Room room) {
        if (center == null) return null;

        RoomLayout layout = room.getLayout();
        List<RoomTile> nearbyTiles = new ArrayList<>();

        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                if (dx == 0 && dy == 0) continue;

                RoomTile tile = layout.getTile((short) (center.x + dx), (short) (center.y + dy));
                if (tile != null && tile.state == RoomTileState.OPEN && !tile.hasUnits()) {
                    nearbyTiles.add(tile);
                }
            }
        }

        return nearbyTiles.isEmpty() ? null : nearbyTiles.get(new Random().nextInt(nearbyTiles.size()));
    }

    private PetPathType determinePathType(PetState state) {
        switch (state.getCurrentBehavior()) {
            case FOLLOWING_OWNER: return PetPathType.FOLLOWING;
            case EXPLORING: return PetPathType.EXPLORING;
            case RESTING: return PetPathType.RESTING;
            case PLAYING: return PetPathType.PLAYING;
            case CURIOUS: return PetPathType.INVESTIGATING;
            default: return PetPathType.WANDERING;
        }
    }

    private String generatePetCacheKey(Pet pet, RoomTile start, RoomTile end) {
        return String.format("pet_%d_%d_%d_%d_%d", pet.getId(), start.x, start.y, end.x, end.y);
    }

    // Supporting classes and enums
    public enum PetBehaviorType {
        FOLLOWING_OWNER, EXPLORING, RESTING, PLAYING, CURIOUS, WANDERING
    }

    public enum PetPathType {
        FOLLOWING, EXPLORING, RESTING, PLAYING, INVESTIGATING, WANDERING, CACHED, FAILED, ERROR, NO_DESTINATION
    }

    public enum InteractionType {
        POSITIVE_OWNER, NEGATIVE_OWNER, POSITIVE_OTHER, NEGATIVE_OTHER, FOOD, TOY, ENVIRONMENT
    }

    // Data classes and results
    public static class PetPathResult {
        private final Deque<RoomTile> path;
        private final PetPathType type;
        private final long computationTime;

        public PetPathResult(Deque<RoomTile> path, PetPathType type, long computationTime) {
            this.path = path;
            this.type = type;
            this.computationTime = computationTime;
        }

        public Deque<RoomTile> getPath() { return path; }
        public PetPathType getType() { return type; }
        public long getComputationTime() { return computationTime; }
    }

    // Placeholder implementations for compilation
    private static class PetBehaviorProfile {
        private final Pet pet;
        private PetBehaviorType primaryBehavior = PetBehaviorType.WANDERING;

        PetBehaviorProfile(Pet pet) {
            this.pet = pet;
        }

        void updatePattern() { /* Implementation */ }
        void recordBehavior(PetBehaviorType behavior, RoomTile location) { /* Implementation */ }
        PetBehaviorType getPrimaryBehavior() { return primaryBehavior; }
        boolean isShyPersonality() { return pet.getType() == 1; } // Cats are shy
        boolean isActivePersonality() { return pet.getType() == 0; } // Dogs are active
        double getLocationPreferenceScore(RoomTile tile) { return 0.5; }
    }

    private static class PetState {
        private final PetBehaviorType currentBehavior;
        private final String currentMood;
        private final int energy;
        private final int happiness;
        private final long timeSinceLastMove;

        PetState(PetBehaviorType behavior, String mood, int energy, int happiness, long timeSinceLastMove) {
            this.currentBehavior = behavior;
            this.currentMood = mood;
            this.energy = energy;
            this.happiness = happiness;
            this.timeSinceLastMove = timeSinceLastMove;
        }

        PetBehaviorType getCurrentBehavior() { return currentBehavior; }
        String getCurrentMood() { return currentMood; }
        int getEnergy() { return energy; }
        int getHappiness() { return happiness; }
        long getTimeSinceLastMove() { return timeSinceLastMove; }
    }

    private static class OwnerRelationship {
        private final Pet pet;

        OwnerRelationship(Pet pet) {
            this.pet = pet;
        }

        int getOptimalFollowDistance() {
            // Different pets prefer different following distances
            switch (pet.getType()) {
                case 0: return 2; // Dogs like to be close
                case 1: return 3; // Cats prefer more distance
                case 2: return 4; // Crocodiles need space
                default: return 2;
            }
        }
    }

    private static class PetEmotion {
        private final Pet pet;
        private String currentMood = "neutral";

        PetEmotion(Pet pet) {
            this.pet = pet;
        }

        void update() { /* Implementation */ }
        void recordInteraction(InteractionType interaction) { /* Implementation */ }
        String getCurrentMood() { return currentMood; }
    }

    private static class PetMovementPredictor {
        void recordPetMovement(Pet pet, Deque<RoomTile> path) { /* Implementation */ }
    }

    private static class PetPathCache {
        private final Map<String, Deque<RoomTile>> cache = new ConcurrentHashMap<>();

        Deque<RoomTile> get(String key) { return cache.get(key); }
        void put(String key, Deque<RoomTile> path) { cache.put(key, new LinkedList<>(path)); }
    }

    private static class SocialInteractionAI {
        // AI for pet-to-pet and pet-to-human interactions
    }

    // Statistics
    public static class BehaviorAnalyzerStats {
        public final long totalAnalyses;
        public final int activePetProfiles;

        public BehaviorAnalyzerStats(long totalAnalyses, int activePetProfiles) {
            this.totalAnalyses = totalAnalyses;
            this.activePetProfiles = activePetProfiles;
        }

        @Override
        public String toString() {
            return String.format("BehaviorAnalyzerStats{analyses=%d, profiles=%d}",
                               totalAnalyses, activePetProfiles);
        }
    }

    // Public API
    public String getComprehensiveStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("=== PET INTELLIGENT PATHFINDING STATS ===\n");
        stats.append("Behavior Analyzer: ").append(behaviorAnalyzer.getStats()).append("\n");
        stats.append("==========================================");
        return stats.toString();
    }

    public void shutdown() {
        petAIScheduler.shutdown();
        petPathfindingExecutor.shutdown();

        try {
            if (!petAIScheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                petAIScheduler.shutdownNow();
            }
            if (!petPathfindingExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                petPathfindingExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            petAIScheduler.shutdownNow();
            petPathfindingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        LOGGER.info("Pet Intelligent Pathfinding System shutdown completed");
    }
}