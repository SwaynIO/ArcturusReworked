package com.eu.habbo.util.pool;

import com.eu.habbo.messages.ServerMessage;

/**
 * Specialized object pool for ServerMessage instances
 * ServerMessages are created very frequently and cause significant GC pressure.
 * This pool reduces allocations by reusing message objects.
 */
public class MessagePool {
    
    // Pool sized for typical server load (~200 concurrent messages)
    private static final ObjectPool<ServerMessage> MESSAGE_POOL = 
        new ObjectPool<>(ServerMessage::new, 200);
    
    /**
     * Get a ServerMessage from the pool
     */
    public static ServerMessage acquire() {
        return MESSAGE_POOL.acquire();
    }
    
    /**
     * Return a ServerMessage to the pool for reuse
     * IMPORTANT: Message must be reset before being returned!
     */
    public static void release(ServerMessage message) {
        if (message != null) {
            // Reset message state before pooling
            message.clear();
            MESSAGE_POOL.release(message);
        }
    }
    
    /**
     * Get pool statistics for monitoring
     */
    public static String getStats() {
        return String.format("MessagePool - Size: %d, Created: %d, Utilization: %.1f%%",
            MESSAGE_POOL.getPoolSize(),
            MESSAGE_POOL.getTotalCreated(), 
            MESSAGE_POOL.getUtilization());
    }
}