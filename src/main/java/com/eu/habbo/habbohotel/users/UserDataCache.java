package com.eu.habbo.habbohotel.users;

import com.eu.habbo.util.cache.CacheManager;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance user data caching system for Arcturus Morningstar Reworked
 * Optimizes user data access with intelligent caching strategies
 */
public class UserDataCache {
    
    // User profile cache with 15-minute TTL
    private static final CacheManager<Integer, Habbo> USER_CACHE = 
        new CacheManager<>(15 * 60 * 1000L);
    
    // User settings cache with 30-minute TTL
    private static final CacheManager<Integer, HabboStats> USER_STATS_CACHE = 
        new CacheManager<>(30 * 60 * 1000L);
    
    // User inventory cache with 10-minute TTL
    private static final CacheManager<Integer, HabboInventory> INVENTORY_CACHE = 
        new CacheManager<>(10 * 60 * 1000L);
    
    // Username to ID mapping cache with 60-minute TTL
    private static final CacheManager<String, Integer> USERNAME_TO_ID_CACHE = 
        new CacheManager<>(60 * 60 * 1000L);
    
    // Online user tracking
    private static final ConcurrentHashMap<Integer, Long> ONLINE_USERS = 
        new ConcurrentHashMap<>(1024, 0.75f, Runtime.getRuntime().availableProcessors());
    
    /**
     * Cache a user
     */
    public static void cacheUser(Habbo user) {
        if (user != null) {
            USER_CACHE.put(user.getHabboInfo().getId(), user);
            if (user.getHabboStats() != null) {
                USER_STATS_CACHE.put(user.getHabboInfo().getId(), user.getHabboStats());
            }
            if (user.getInventory() != null) {
                INVENTORY_CACHE.put(user.getHabboInfo().getId(), user.getInventory());
            }
            USERNAME_TO_ID_CACHE.put(user.getHabboInfo().getUsername().toLowerCase(), 
                                   user.getHabboInfo().getId());
        }
    }
    
    /**
     * Get cached user
     */
    public static Habbo getCachedUser(int userId) {
        return USER_CACHE.get(userId, () -> null); // Don't auto-load from DB
    }
    
    /**
     * Get cached user stats
     */
    public static HabboStats getCachedUserStats(int userId) {
        return USER_STATS_CACHE.get(userId, () -> null);
    }
    
    /**
     * Get cached user inventory
     */
    public static HabboInventory getCachedInventory(int userId) {
        return INVENTORY_CACHE.get(userId, () -> null);
    }
    
    /**
     * Get user ID by username
     */
    public static Integer getUserIdByUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            return null;
        }
        return USERNAME_TO_ID_CACHE.get(username.toLowerCase(), () -> null);
    }
    
    /**
     * Cache username to ID mapping
     */
    public static void cacheUsernameMapping(String username, int userId) {
        if (username != null && !username.trim().isEmpty()) {
            USERNAME_TO_ID_CACHE.put(username.toLowerCase(), userId);
        }
    }
    
    /**
     * Mark user as online
     */
    public static void markUserOnline(int userId) {
        ONLINE_USERS.put(userId, System.currentTimeMillis());
    }
    
    /**
     * Mark user as offline
     */
    public static void markUserOffline(int userId) {
        ONLINE_USERS.remove(userId);
    }
    
    /**
     * Check if user is online
     */
    public static boolean isUserOnline(int userId) {
        Long lastSeen = ONLINE_USERS.get(userId);
        if (lastSeen == null) return false;
        
        // Consider user offline if not seen in last 5 minutes
        return (System.currentTimeMillis() - lastSeen) < (5 * 60 * 1000);
    }
    
    /**
     * Get online user count
     */
    public static int getOnlineUserCount() {
        // Clean up stale entries
        long cutoff = System.currentTimeMillis() - (5 * 60 * 1000);
        ONLINE_USERS.entrySet().removeIf(entry -> entry.getValue() < cutoff);
        return ONLINE_USERS.size();
    }
    
    /**
     * Remove user from all caches
     */
    public static void removeUser(int userId) {
        USER_CACHE.remove(userId);
        USER_STATS_CACHE.remove(userId);
        INVENTORY_CACHE.remove(userId);
        ONLINE_USERS.remove(userId);
        
        // Note: We don't remove from USERNAME_TO_ID_CACHE as usernames don't change frequently
    }
    
    /**
     * Remove user by username
     */
    public static void removeUserByUsername(String username) {
        if (username != null && !username.trim().isEmpty()) {
            Integer userId = USERNAME_TO_ID_CACHE.get(username.toLowerCase(), () -> null);
            if (userId != null) {
                removeUser(userId);
            }
            USERNAME_TO_ID_CACHE.remove(username.toLowerCase());
        }
    }
    
    /**
     * Get cache statistics
     */
    public static String getCacheStats() {
        return String.format(
            "User Cache Stats - Users: %d, Stats: %d, Inventories: %d, Username mappings: %d, Online: %d",
            USER_CACHE.size(),
            USER_STATS_CACHE.size(),
            INVENTORY_CACHE.size(),
            USERNAME_TO_ID_CACHE.size(),
            getOnlineUserCount()
        );
    }
    
    /**
     * Clear all user caches
     */
    public static void clearAll() {
        USER_CACHE.clear();
        USER_STATS_CACHE.clear();
        INVENTORY_CACHE.clear();
        USERNAME_TO_ID_CACHE.clear();
        ONLINE_USERS.clear();
    }
}