package com.eu.habbo.habbohotel.rooms;

import com.eu.habbo.util.cache.CacheManager;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enhanced room caching system for Arcturus Morningstar Reworked
 * Provides high-performance caching for frequently accessed rooms
 */
public class RoomCache {
    
    // Room data cache with 30-minute TTL
    private static final CacheManager<Integer, Room> ROOM_CACHE = 
        new CacheManager<>(30 * 60 * 1000L); // 30 minutes
    
    // Room metadata cache with 5-minute TTL  
    private static final CacheManager<Integer, RoomData> ROOM_DATA_CACHE = 
        new CacheManager<>(5 * 60 * 1000L); // 5 minutes
    
    // Room user count cache with 1-minute TTL
    private static final ConcurrentHashMap<Integer, AtomicInteger> USER_COUNTS = 
        new ConcurrentHashMap<>(512, 0.75f, Runtime.getRuntime().availableProcessors());
    
    /**
     * Get room from cache or load from database
     */
    public static Room getRoom(int roomId) {
        return ROOM_CACHE.get(roomId, () -> loadRoomFromDatabase(roomId));
    }
    
    /**
     * Get room data from cache or load from database
     */
    public static RoomData getRoomData(int roomId) {
        return ROOM_DATA_CACHE.get(roomId, () -> loadRoomDataFromDatabase(roomId));
    }
    
    /**
     * Cache a room
     */
    public static void cacheRoom(Room room) {
        if (room != null) {
            ROOM_CACHE.put(room.getId(), room);
            if (room.getRoomInfo() != null) {
                ROOM_DATA_CACHE.put(room.getId(), room.getRoomInfo());
            }
        }
    }
    
    /**
     * Remove room from cache
     */
    public static void uncacheRoom(int roomId) {
        ROOM_CACHE.remove(roomId);
        ROOM_DATA_CACHE.remove(roomId);
        USER_COUNTS.remove(roomId);
    }
    
    /**
     * Get cached user count for room
     */
    public static int getCachedUserCount(int roomId) {
        AtomicInteger count = USER_COUNTS.get(roomId);
        return count != null ? count.get() : 0;
    }
    
    /**
     * Update cached user count
     */
    public static void updateUserCount(int roomId, int count) {
        USER_COUNTS.computeIfAbsent(roomId, k -> new AtomicInteger()).set(count);
    }
    
    /**
     * Increment user count
     */
    public static int incrementUserCount(int roomId) {
        return USER_COUNTS.computeIfAbsent(roomId, k -> new AtomicInteger()).incrementAndGet();
    }
    
    /**
     * Decrement user count
     */
    public static int decrementUserCount(int roomId) {
        AtomicInteger count = USER_COUNTS.get(roomId);
        if (count != null) {
            int newCount = count.decrementAndGet();
            if (newCount <= 0) {
                USER_COUNTS.remove(roomId);
                return 0;
            }
            return newCount;
        }
        return 0;
    }
    
    /**
     * Get cache statistics
     */
    public static String getCacheStats() {
        return String.format(
            "Room Cache Stats - Rooms: %d, Room Data: %d, User Counts: %d",
            ROOM_CACHE.size(),
            ROOM_DATA_CACHE.size(), 
            USER_COUNTS.size()
        );
    }
    
    /**
     * Clear all room caches
     */
    public static void clearAll() {
        ROOM_CACHE.clear();
        ROOM_DATA_CACHE.clear();
        USER_COUNTS.clear();
    }
    
    // Placeholder methods - would need actual implementation
    private static Room loadRoomFromDatabase(int roomId) {
        // Implementation would load from database via RoomManager
        return null;
    }
    
    private static RoomData loadRoomDataFromDatabase(int roomId) {
        // Implementation would load room data from database
        return null;
    }
}