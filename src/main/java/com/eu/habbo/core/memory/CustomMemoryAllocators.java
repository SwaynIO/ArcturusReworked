package com.eu.habbo.core.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.*;

/**
 * Custom memory allocators with intelligent memory management and leak detection
 * Provides specialized allocators for different object types and usage patterns
 */
public class CustomMemoryAllocators {
    private static final Logger LOGGER = LoggerFactory.getLogger(CustomMemoryAllocators.class);
    
    private static CustomMemoryAllocators instance;
    
    private final DirectMemoryAllocator directMemoryAllocator;
    private final PooledObjectAllocator pooledObjectAllocator;
    private final ArenaAllocator arenaAllocator;
    private final SmallObjectAllocator smallObjectAllocator;
    private final MemoryLeakDetector leakDetector;
    private final MemoryUsageTracker usageTracker;
    private final ScheduledExecutorService maintenanceScheduler;
    
    // Configuration
    private static final long MAINTENANCE_INTERVAL = 30; // seconds
    private static final int DIRECT_MEMORY_POOL_SIZE = 64;
    private static final int ARENA_SIZE = 1024 * 1024; // 1MB arenas
    
    private CustomMemoryAllocators() {
        this.directMemoryAllocator = new DirectMemoryAllocator();
        this.pooledObjectAllocator = new PooledObjectAllocator();
        this.arenaAllocator = new ArenaAllocator();
        this.smallObjectAllocator = new SmallObjectAllocator();
        this.leakDetector = new MemoryLeakDetector();
        this.usageTracker = new MemoryUsageTracker();
        
        this.maintenanceScheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "MemoryAllocator-Maintenance");
            t.setDaemon(true);
            return t;
        });
        
        startMaintenance();
        LOGGER.info("Custom Memory Allocators initialized");
    }
    
    public static synchronized CustomMemoryAllocators getInstance() {
        if (instance == null) {
            instance = new CustomMemoryAllocators();
        }
        return instance;
    }
    
    private void startMaintenance() {
        // Regular memory pool maintenance
        maintenanceScheduler.scheduleWithFixedDelay(this::performMaintenance,
            MAINTENANCE_INTERVAL, MAINTENANCE_INTERVAL, TimeUnit.SECONDS);
        
        // Leak detection
        maintenanceScheduler.scheduleWithFixedDelay(leakDetector::detectLeaks,
            60, 60, TimeUnit.SECONDS);
    }
    
    /**
     * High-performance direct memory allocator with pooling
     */
    public class DirectMemoryAllocator {
        private final ConcurrentLinkedQueue<ManagedByteBuffer> availableBuffers = new ConcurrentLinkedQueue<>();
        private final ConcurrentHashMap<Integer, BufferPool> sizeBasedPools = new ConcurrentHashMap<>();
        private final AtomicLong totalAllocated = new AtomicLong();
        private final AtomicLong totalDeallocated = new AtomicLong();
        
        public ManagedByteBuffer allocate(int size) {
            usageTracker.recordAllocation(size);
            
            // Try to get from appropriate sized pool first
            BufferPool pool = sizeBasedPools.computeIfAbsent(normalizeSize(size), k -> new BufferPool(k));
            ManagedByteBuffer buffer = pool.acquire();
            
            if (buffer == null) {
                // Create new buffer
                ByteBuffer nativeBuffer = ByteBuffer.allocateDirect(size);
                buffer = new ManagedByteBuffer(nativeBuffer, size, this);
                totalAllocated.addAndGet(size);
            }
            
            leakDetector.trackAllocation(buffer);
            return buffer;
        }
        
        void deallocate(ManagedByteBuffer buffer) {
            if (buffer == null || buffer.isReleased()) {
                return;
            }
            
            buffer.clear();
            usageTracker.recordDeallocation(buffer.getSize());
            leakDetector.trackDeallocation(buffer);
            
            // Return to appropriate pool
            int normalizedSize = normalizeSize(buffer.getSize());
            BufferPool pool = sizeBasedPools.get(normalizedSize);
            if (pool != null && pool.canAccept()) {
                pool.release(buffer);
            } else {
                // Buffer will be garbage collected
                totalDeallocated.addAndGet(buffer.getSize());
            }
            
            buffer.markReleased();
        }
        
        private int normalizeSize(int size) {
            // Round up to next power of 2 for efficient pooling
            return Integer.highestOneBit(size - 1) << 1;
        }
        
        public DirectMemoryStats getStats() {
            long allocated = totalAllocated.get();
            long deallocated = totalDeallocated.get();
            int pooledBuffers = sizeBasedPools.values().stream()
                .mapToInt(BufferPool::size)
                .sum();
            
            return new DirectMemoryStats(allocated, deallocated, allocated - deallocated, pooledBuffers);
        }
    }
    
    /**
     * Specialized object pooling with type-aware allocation
     */
    public class PooledObjectAllocator {
        private final Map<Class<?>, ObjectPool<?>> typePools = new ConcurrentHashMap<>();
        private final LongAdder totalBorrows = new LongAdder();
        private final LongAdder totalReturns = new LongAdder();
        
        @SuppressWarnings("unchecked")
        public <T> T borrow(Class<T> clazz, ObjectFactory<T> factory) {
            ObjectPool<T> pool = (ObjectPool<T>) typePools.computeIfAbsent(clazz, 
                k -> new ObjectPool<>(factory, 32));
            
            T object = pool.borrow();
            totalBorrows.increment();
            usageTracker.recordObjectBorrow(clazz);
            
            return object;
        }
        
        public <T> void returnObject(Class<T> clazz, T object) {
            @SuppressWarnings("unchecked")
            ObjectPool<T> pool = (ObjectPool<T>) typePools.get(clazz);
            if (pool != null) {
                pool.returnObject(object);
                totalReturns.increment();
                usageTracker.recordObjectReturn(clazz);
            }
        }
        
        public PooledObjectStats getStats() {
            Map<String, Integer> poolSizes = new HashMap<>();
            for (Map.Entry<Class<?>, ObjectPool<?>> entry : typePools.entrySet()) {
                poolSizes.put(entry.getKey().getSimpleName(), entry.getValue().size());
            }
            
            return new PooledObjectStats(totalBorrows.sum(), totalReturns.sum(), poolSizes);
        }
    }
    
    /**
     * Arena-based allocator for bulk memory allocation
     */
    public class ArenaAllocator {
        private final List<MemoryArena> arenas = new CopyOnWriteArrayList<>();
        private final AtomicInteger currentArenaIndex = new AtomicInteger(0);
        private final LongAdder totalArenaAllocations = new LongAdder();
        
        public ArenaAllocation allocate(int size) {
            if (size > ARENA_SIZE / 2) {
                // Large allocation - use dedicated arena
                return allocateDedicated(size);
            }
            
            // Try current arena first
            MemoryArena currentArena = getCurrentArena();
            ArenaAllocation allocation = currentArena.allocate(size);
            
            if (allocation == null) {
                // Arena full, try next or create new
                currentArena = getNextArena();
                allocation = currentArena.allocate(size);
            }
            
            if (allocation != null) {
                totalArenaAllocations.increment();
                usageTracker.recordArenaAllocation(size);
            }
            
            return allocation;
        }
        
        private ArenaAllocation allocateDedicated(int size) {
            MemoryArena dedicatedArena = new MemoryArena(size, true);
            arenas.add(dedicatedArena);
            return dedicatedArena.allocate(size);
        }
        
        private MemoryArena getCurrentArena() {
            if (arenas.isEmpty()) {
                arenas.add(new MemoryArena(ARENA_SIZE, false));
            }
            
            int index = currentArenaIndex.get() % arenas.size();
            return arenas.get(index);
        }
        
        private MemoryArena getNextArena() {
            // Try next existing arena
            for (int i = 0; i < arenas.size(); i++) {
                int nextIndex = currentArenaIndex.incrementAndGet() % arenas.size();
                MemoryArena arena = arenas.get(nextIndex);
                if (arena.hasSpace()) {
                    return arena;
                }
            }
            
            // All arenas full, create new one
            MemoryArena newArena = new MemoryArena(ARENA_SIZE, false);
            arenas.add(newArena);
            return newArena;
        }
        
        public void resetArenas() {
            for (MemoryArena arena : arenas) {
                if (!arena.isDedicated()) {
                    arena.reset();
                }
            }
        }
        
        public ArenaStats getStats() {
            int totalArenas = arenas.size();
            long totalCapacity = arenas.stream().mapToLong(MemoryArena::getCapacity).sum();
            long totalUsed = arenas.stream().mapToLong(MemoryArena::getUsed).sum();
            
            return new ArenaStats(totalArenas, totalCapacity, totalUsed, totalArenaAllocations.sum());
        }
    }
    
    /**
     * Optimized allocator for small, frequently allocated objects
     */
    public class SmallObjectAllocator {
        private static final int MAX_SMALL_OBJECT_SIZE = 256;
        private final SmallObjectPool[] sizePools = new SmallObjectPool[MAX_SMALL_OBJECT_SIZE + 1];
        private final LongAdder totalSmallAllocations = new LongAdder();
        
        SmallObjectAllocator() {
            for (int i = 0; i <= MAX_SMALL_OBJECT_SIZE; i++) {
                sizePools[i] = new SmallObjectPool(i);
            }
        }
        
        public SmallObjectAllocation allocate(int size) {
            if (size > MAX_SMALL_OBJECT_SIZE) {
                return null; // Use regular allocator
            }
            
            SmallObjectPool pool = sizePools[size];
            SmallObjectAllocation allocation = pool.allocate();
            
            if (allocation != null) {
                totalSmallAllocations.increment();
                usageTracker.recordSmallObjectAllocation(size);
            }
            
            return allocation;
        }
        
        public void deallocate(SmallObjectAllocation allocation) {
            if (allocation != null && allocation.getSize() <= MAX_SMALL_OBJECT_SIZE) {
                SmallObjectPool pool = sizePools[allocation.getSize()];
                pool.deallocate(allocation);
            }
        }
        
        public SmallObjectStats getStats() {
            long totalPooled = Arrays.stream(sizePools)
                .mapToLong(SmallObjectPool::getPooledCount)
                .sum();
            
            return new SmallObjectStats(totalSmallAllocations.sum(), totalPooled);
        }
    }
    
    /**
     * Advanced memory leak detection system
     */
    public class MemoryLeakDetector {
        private final Map<Object, AllocationInfo> trackedAllocations = new ConcurrentHashMap<>();
        private final ReferenceQueue<Object> referenceQueue = new ReferenceQueue<>();
        private final LongAdder suspiciousAllocations = new LongAdder();
        
        void trackAllocation(Object allocation) {
            AllocationInfo info = new AllocationInfo(System.currentTimeMillis(), 
                Thread.currentThread().getStackTrace());
            
            trackedAllocations.put(allocation, info);
            
            // Create phantom reference for cleanup detection
            new AllocationPhantomReference(allocation, referenceQueue, info);
        }
        
        void trackDeallocation(Object allocation) {
            trackedAllocations.remove(allocation);
        }
        
        void detectLeaks() {
            long currentTime = System.currentTimeMillis();
            long suspiciousThreshold = 10 * 60 * 1000; // 10 minutes
            
            for (Map.Entry<Object, AllocationInfo> entry : trackedAllocations.entrySet()) {
                AllocationInfo info = entry.getValue();
                if (currentTime - info.allocationTime > suspiciousThreshold) {
                    suspiciousAllocations.increment();
                    LOGGER.warn("Potential memory leak detected: allocation age = {} ms", 
                              currentTime - info.allocationTime);
                }
            }
            
            // Process phantom references
            processPhantomReferences();
        }
        
        private void processPhantomReferences() {
            AllocationPhantomReference ref;
            while ((ref = (AllocationPhantomReference) referenceQueue.poll()) != null) {
                trackedAllocations.remove(ref.getAllocationInfo());
                ref.clear();
            }
        }
        
        public LeakDetectionStats getStats() {
            return new LeakDetectionStats(
                trackedAllocations.size(),
                suspiciousAllocations.sum()
            );
        }
    }
    
    /**
     * Comprehensive memory usage tracking
     */
    public class MemoryUsageTracker {
        private final LongAdder totalAllocations = new LongAdder();
        private final LongAdder totalDeallocations = new LongAdder();
        private final AtomicLong totalBytesAllocated = new AtomicLong();
        private final AtomicLong totalBytesFreed = new AtomicLong();
        private final Map<Class<?>, LongAdder> objectBorrows = new ConcurrentHashMap<>();
        private final Map<Class<?>, LongAdder> objectReturns = new ConcurrentHashMap<>();
        
        void recordAllocation(int size) {
            totalAllocations.increment();
            totalBytesAllocated.addAndGet(size);
        }
        
        void recordDeallocation(int size) {
            totalDeallocations.increment();
            totalBytesFreed.addAndGet(size);
        }
        
        void recordObjectBorrow(Class<?> clazz) {
            objectBorrows.computeIfAbsent(clazz, k -> new LongAdder()).increment();
        }
        
        void recordObjectReturn(Class<?> clazz) {
            objectReturns.computeIfAbsent(clazz, k -> new LongAdder()).increment();
        }
        
        void recordArenaAllocation(int size) {
            // Could track arena-specific metrics
        }
        
        void recordSmallObjectAllocation(int size) {
            // Could track small object-specific metrics
        }
        
        public MemoryUsageStats getStats() {
            return new MemoryUsageStats(
                totalAllocations.sum(),
                totalDeallocations.sum(),
                totalBytesAllocated.get(),
                totalBytesFreed.get()
            );
        }
    }
    
    // Supporting classes
    private class BufferPool {
        private final int bufferSize;
        private final Queue<ManagedByteBuffer> pool = new ConcurrentLinkedQueue<>();
        private final AtomicInteger poolSize = new AtomicInteger();
        private static final int MAX_POOL_SIZE = 32;
        
        BufferPool(int bufferSize) {
            this.bufferSize = bufferSize;
        }
        
        ManagedByteBuffer acquire() {
            ManagedByteBuffer buffer = pool.poll();
            if (buffer != null) {
                poolSize.decrementAndGet();
                buffer.clear();
            }
            return buffer;
        }
        
        void release(ManagedByteBuffer buffer) {
            if (poolSize.get() < MAX_POOL_SIZE && buffer.getSize() == bufferSize) {
                pool.offer(buffer);
                poolSize.incrementAndGet();
            }
        }
        
        boolean canAccept() {
            return poolSize.get() < MAX_POOL_SIZE;
        }
        
        int size() {
            return poolSize.get();
        }
    }
    
    public static class ManagedByteBuffer {
        private final ByteBuffer buffer;
        private final int size;
        private final DirectMemoryAllocator allocator;
        private volatile boolean released = false;
        
        ManagedByteBuffer(ByteBuffer buffer, int size, DirectMemoryAllocator allocator) {
            this.buffer = buffer;
            this.size = size;
            this.allocator = allocator;
        }
        
        public ByteBuffer getBuffer() {
            if (released) {
                throw new IllegalStateException("Buffer has been released");
            }
            return buffer;
        }
        
        public int getSize() { return size; }
        public boolean isReleased() { return released; }
        
        public void release() {
            if (!released) {
                allocator.deallocate(this);
            }
        }
        
        void markReleased() { released = true; }
        void clear() { buffer.clear(); }
    }
    
    @FunctionalInterface
    public interface ObjectFactory<T> {
        T create();
    }
    
    private static class ObjectPool<T> {
        private final ObjectFactory<T> factory;
        private final Queue<T> pool = new ConcurrentLinkedQueue<>();
        private final AtomicInteger poolSize = new AtomicInteger();
        private final int maxSize;
        
        ObjectPool(ObjectFactory<T> factory, int maxSize) {
            this.factory = factory;
            this.maxSize = maxSize;
        }
        
        T borrow() {
            T object = pool.poll();
            if (object != null) {
                poolSize.decrementAndGet();
            } else {
                object = factory.create();
            }
            return object;
        }
        
        void returnObject(T object) {
            if (poolSize.get() < maxSize) {
                pool.offer(object);
                poolSize.incrementAndGet();
            }
        }
        
        int size() {
            return poolSize.get();
        }
    }
    
    // Statistics classes
    public static class DirectMemoryStats {
        public final long totalAllocated;
        public final long totalDeallocated;
        public final long currentlyAllocated;
        public final int pooledBuffers;
        
        public DirectMemoryStats(long totalAllocated, long totalDeallocated, 
                               long currentlyAllocated, int pooledBuffers) {
            this.totalAllocated = totalAllocated;
            this.totalDeallocated = totalDeallocated;
            this.currentlyAllocated = currentlyAllocated;
            this.pooledBuffers = pooledBuffers;
        }
        
        @Override
        public String toString() {
            return String.format("DirectMemoryStats{allocated=%d MB, current=%d MB, pooled=%d}",
                totalAllocated / 1024 / 1024, currentlyAllocated / 1024 / 1024, pooledBuffers);
        }
    }
    
    public static class MemoryUsageStats {
        public final long totalAllocations;
        public final long totalDeallocations;
        public final long totalBytesAllocated;
        public final long totalBytesFreed;
        
        public MemoryUsageStats(long totalAllocations, long totalDeallocations,
                              long totalBytesAllocated, long totalBytesFreed) {
            this.totalAllocations = totalAllocations;
            this.totalDeallocations = totalDeallocations;
            this.totalBytesAllocated = totalBytesAllocated;
            this.totalBytesFreed = totalBytesFreed;
        }
        
        @Override
        public String toString() {
            return String.format("MemoryUsageStats{allocations=%d, bytes=%.2f MB}",
                totalAllocations, totalBytesAllocated / 1024.0 / 1024.0);
        }
    }
    
    // Placeholder classes for additional components
    private static class MemoryArena {
        private final ByteBuffer arena;
        private final AtomicInteger position = new AtomicInteger();
        private final boolean dedicated;
        
        MemoryArena(int size, boolean dedicated) {
            this.arena = ByteBuffer.allocateDirect(size);
            this.dedicated = dedicated;
        }
        
        ArenaAllocation allocate(int size) {
            int currentPos = position.get();
            if (currentPos + size > arena.capacity()) {
                return null; // Arena full
            }
            
            if (position.compareAndSet(currentPos, currentPos + size)) {
                return new ArenaAllocation(arena, currentPos, size);
            }
            
            return null; // Concurrent modification
        }
        
        boolean hasSpace() { return position.get() < arena.capacity() / 2; }
        boolean isDedicated() { return dedicated; }
        void reset() { if (!dedicated) position.set(0); }
        long getCapacity() { return arena.capacity(); }
        long getUsed() { return position.get(); }
    }
    
    public static class ArenaAllocation {
        private final ByteBuffer buffer;
        private final int offset;
        private final int size;
        
        ArenaAllocation(ByteBuffer arena, int offset, int size) {
            this.buffer = arena.slice();
            this.buffer.position(offset);
            this.buffer.limit(offset + size);
            this.offset = offset;
            this.size = size;
        }
        
        public ByteBuffer getBuffer() { return buffer.slice(); }
        public int getSize() { return size; }
    }
    
    // Additional placeholder classes
    private static class SmallObjectPool {
        private final int size;
        private final Queue<SmallObjectAllocation> pool = new ConcurrentLinkedQueue<>();
        private final AtomicLong pooledCount = new AtomicLong();
        
        SmallObjectPool(int size) { this.size = size; }
        
        SmallObjectAllocation allocate() {
            SmallObjectAllocation allocation = pool.poll();
            if (allocation == null) {
                allocation = new SmallObjectAllocation(new byte[size]);
            } else {
                pooledCount.decrementAndGet();
            }
            return allocation;
        }
        
        void deallocate(SmallObjectAllocation allocation) {
            pool.offer(allocation);
            pooledCount.incrementAndGet();
        }
        
        long getPooledCount() { return pooledCount.get(); }
    }
    
    public static class SmallObjectAllocation {
        private final byte[] data;
        
        SmallObjectAllocation(byte[] data) { this.data = data; }
        public byte[] getData() { return data; }
        public int getSize() { return data.length; }
    }
    
    private static class AllocationInfo {
        final long allocationTime;
        final StackTraceElement[] stackTrace;
        
        AllocationInfo(long allocationTime, StackTraceElement[] stackTrace) {
            this.allocationTime = allocationTime;
            this.stackTrace = stackTrace;
        }
    }
    
    private static class AllocationPhantomReference extends PhantomReference<Object> {
        private final AllocationInfo allocationInfo;
        
        AllocationPhantomReference(Object referent, ReferenceQueue<? super Object> queue, 
                                 AllocationInfo allocationInfo) {
            super(referent, queue);
            this.allocationInfo = allocationInfo;
        }
        
        AllocationInfo getAllocationInfo() { return allocationInfo; }
    }
    
    // Additional stats classes
    public static class PooledObjectStats {
        public final long totalBorrows;
        public final long totalReturns; 
        public final Map<String, Integer> poolSizes;
        
        public PooledObjectStats(long totalBorrows, long totalReturns, Map<String, Integer> poolSizes) {
            this.totalBorrows = totalBorrows;
            this.totalReturns = totalReturns;
            this.poolSizes = poolSizes;
        }
        
        @Override
        public String toString() {
            return String.format("PooledObjectStats{borrows=%d, returns=%d, pools=%d}",
                totalBorrows, totalReturns, poolSizes.size());
        }
    }
    
    public static class ArenaStats {
        public final int totalArenas;
        public final long totalCapacity;
        public final long totalUsed;
        public final long totalAllocations;
        
        public ArenaStats(int totalArenas, long totalCapacity, long totalUsed, long totalAllocations) {
            this.totalArenas = totalArenas;
            this.totalCapacity = totalCapacity;
            this.totalUsed = totalUsed;
            this.totalAllocations = totalAllocations;
        }
        
        @Override
        public String toString() {
            return String.format("ArenaStats{arenas=%d, used=%.1f%%, allocations=%d}",
                totalArenas, (double) totalUsed / totalCapacity * 100, totalAllocations);
        }
    }
    
    public static class SmallObjectStats {
        public final long totalAllocations;
        public final long totalPooled;
        
        public SmallObjectStats(long totalAllocations, long totalPooled) {
            this.totalAllocations = totalAllocations;
            this.totalPooled = totalPooled;
        }
        
        @Override
        public String toString() {
            return String.format("SmallObjectStats{allocations=%d, pooled=%d}",
                totalAllocations, totalPooled);
        }
    }
    
    public static class LeakDetectionStats {
        public final int trackedAllocations;
        public final long suspiciousAllocations;
        
        public LeakDetectionStats(int trackedAllocations, long suspiciousAllocations) {
            this.trackedAllocations = trackedAllocations;
            this.suspiciousAllocations = suspiciousAllocations;
        }
        
        @Override
        public String toString() {
            return String.format("LeakDetectionStats{tracked=%d, suspicious=%d}",
                trackedAllocations, suspiciousAllocations);
        }
    }
    
    private void performMaintenance() {
        // Pool cleanup and optimization
        directMemoryAllocator.getStats();
        arenaAllocator.resetArenas();
        
        LOGGER.debug("Memory allocator maintenance completed");
    }
    
    // Public API methods
    public DirectMemoryAllocator getDirectMemoryAllocator() { return directMemoryAllocator; }
    public PooledObjectAllocator getPooledObjectAllocator() { return pooledObjectAllocator; }
    public ArenaAllocator getArenaAllocator() { return arenaAllocator; }
    public SmallObjectAllocator getSmallObjectAllocator() { return smallObjectAllocator; }
    
    public String getComprehensiveStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("=== CUSTOM MEMORY ALLOCATORS STATS ===\n");
        stats.append("Direct Memory: ").append(directMemoryAllocator.getStats()).append("\n");
        stats.append("Pooled Objects: ").append(pooledObjectAllocator.getStats()).append("\n");
        stats.append("Arena: ").append(arenaAllocator.getStats()).append("\n");
        stats.append("Small Objects: ").append(smallObjectAllocator.getStats()).append("\n");
        stats.append("Memory Usage: ").append(usageTracker.getStats()).append("\n");
        stats.append("Leak Detection: ").append(leakDetector.getStats()).append("\n");
        stats.append("=====================================");
        
        return stats.toString();
    }
    
    public void shutdown() {
        maintenanceScheduler.shutdown();
        try {
            if (!maintenanceScheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                maintenanceScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            maintenanceScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        LOGGER.info("Custom Memory Allocators shutdown completed");
    }
}