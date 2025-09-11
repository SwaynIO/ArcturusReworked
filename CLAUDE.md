# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

This is a Maven-based Java project. Use the following commands:

```bash
# Compile the project
mvn compile

# Build JAR with dependencies
mvn clean package

# Run the server (after building)
java -jar target/Habbo-3.5.3-jar-with-dependencies.jar
```

The main class is `com.eu.habbo.Emulator` as defined in pom.xml:52.

## Architecture Overview

**Arcturus Morningstar Reworked** is a security-hardened and performance-optimized Habbo Hotel server emulator (version 3.5.3) written in Java 8, built on a plugin-based architecture.

### Core Components

- **Emulator.java** - Main entry point and application bootstrap
- **GameEnvironment** - Central game state manager containing all major subsystems
- **Plugin System** - Extensible event-driven plugin architecture for custom features
- **Networking Layer** - Netty-based server handling client connections and packet processing
- **Database Layer** - HikariCP connection pooling with MySQL backend

### Package Structure

- `com.eu.habbo.core` - Core services (configuration, logging, scheduling, text management)
- `com.eu.habbo.habbohotel` - Game logic modules:
  - `users` - User management, profiles, inventory, badges
  - `rooms` - Room system, furniture, chat, moderation  
  - `items` - Furniture definitions, interactions, wired system
  - `pets` - Pet system with breeding, commands, AI
  - `messenger` - Friends system and private messaging
  - `modtool` - Moderation tools, sanctions, chat logging
  - `permissions` - Role-based access control
  - `polls` - Survey/polling system
- `com.eu.habbo.networking` - Network protocols and packet handlers
- `com.eu.habbo.messages` - Client-server packet definitions
- `com.eu.habbo.plugin` - Plugin API and event system
- `com.eu.habbo.threading` - Background task execution
- `com.eu.habbo.database` - Database abstraction layer

### Key Dependencies

- **Netty 4.1.49** - Asynchronous networking framework
- **MySQL Connector 8.0.22** - Database connectivity
- **HikariCP 3.4.3** - High-performance connection pooling
- **GSON 2.8.6** - JSON processing
- **Logback 1.2.3** - Logging framework
- **Trove4j 3.0.3** - High-performance collections

### Configuration

The server uses a database-driven configuration system managed by `ConfigurationManager`. Configuration values are stored in the database and loaded at runtime.

### Plugin Development

Plugins extend `HabboPlugin` and use annotations like `@EventHandler` for event handling. The plugin system supports hot-loading and provides extensive hooks into the game engine.

## Security & Performance Enhancements

**Arcturus Morningstar Reworked** includes critical security fixes and comprehensive performance optimizations:

### Security Improvements
- **SQL Injection Protection**: Fixed parameterized queries in `RoomManager.findRooms()`
- **Password Security**: Enhanced credential handling in `CameraLoginComposer`
- **Input Validation**: Strengthened parameter validation across packet handlers
- **Thread Safety**: Replaced non-thread-safe collections with concurrent alternatives

### Performance Optimizations

#### Database & Connection Management
- **Database Pool**: Optimized HikariCP configuration with better caching and timeouts
- **Connection Management**: Reduced connection timeout from 300s to 30s
- **Prepared Statement Cache**: Increased from 500 to 1000 statements
- **Query Optimization**: Added pattern caching and improved parameter binding in `Database.java`

#### Memory & Object Management
- **Object Pooling**: New `ObjectPool<T>` utility for reducing garbage collection pressure
- **StringBuilder Pool**: `StringBuilderPool` for efficient string operations
- **Memory Monitoring**: `PerformanceMonitor` tracks memory usage and GC performance
- **Smart Sizing**: Pre-sized collections with optimal capacity and concurrency levels

#### Threading & Concurrency
- **Thread Pool Optimization**: Enhanced `HabboExecutorService` with dynamic sizing
- **Concurrent Collections**: Upgraded critical managers to use `ConcurrentHashMap`
- **Thread Management**: Improved thread naming, daemon settings, and lifecycle management
- **Message Batching**: New `MessageBatchProcessor` for high-throughput message handling

#### Caching Systems
- **Multi-Level Cache**: Enhanced `CacheManager<K,V>` with TTL-based eviction
- **Room Cache**: `RoomCache` provides intelligent room data caching
- **User Cache**: `UserDataCache` optimizes user profile and inventory access
- **Cache Statistics**: Built-in monitoring and performance reporting

#### Performance Monitoring
- **Real-time Metrics**: `PerformanceUtils` tracks packets, queries, and operations
- **Automated Monitoring**: Periodic performance reports and alert system
- **Emergency Cleanup**: Automatic memory pressure relief mechanisms
- **JVM Optimization**: GC monitoring and heap usage tracking

#### Advanced Threading & Task Management
- **SafeRunnable**: Enhanced task wrapper with error isolation and performance tracking
- **RetryableRunnable**: Automatic retry logic with exponential backoff
- **Task Statistics**: Comprehensive timing and execution metrics
- **Smart Scheduling**: Optimized task distribution and load balancing

#### Intelligent Data Structures
- **OptimizedConcurrentMap**: Performance-monitored concurrent maps with hit rate tracking
- **HighPerformanceQueue**: MPSC queues with different strategies (Array, Linked, Priority)
- **Queue Monitoring**: Real-time utilization and throughput metrics
- **Adaptive Sizing**: Dynamic capacity adjustment based on load patterns

#### Real-Time Metrics System
- **RealTimeMetrics**: Comprehensive metrics collection with minimal overhead
- **MetricTypes**: Support for Counters, Gauges, and Histograms
- **TimerMetrics**: High-precision operation timing with statistical analysis
- **Exponential Averages**: Smooth trend tracking for performance indicators

#### Automatic Optimization
- **AutoOptimizer**: Intelligent performance tuning based on real-time metrics
- **Adaptive Algorithms**: Self-learning optimization patterns
- **Memory Management**: Automatic GC triggering and cache cleanup
- **Load Balancing**: Dynamic resource allocation optimization

#### Advanced Network & Packet Optimization
- **PacketCompression**: Intelligent compression with adaptive algorithms and statistics
- **Compression Analytics**: Per-packet-type compression analysis with learning capabilities
- **Network Bottleneck Detection**: Automatic identification and mitigation of network issues
- **Packet Batching**: High-throughput packet processing with optimized batching strategies

#### Intelligent Load Balancing
- **IntelligentLoadBalancer**: Multi-strategy load balancing with real-time adaptation
- **WorkerNode Management**: Dynamic worker health monitoring and capacity tracking  
- **Circuit Breaker Pattern**: Automatic failure detection and service protection
- **Adaptive Strategies**: Round-robin, weighted, least-connections, least-response-time, and hybrid algorithms

#### Distributed Cache System
- **DistributedCache**: Multi-tier caching (L1: hot, L2: warm, L3: compressed)
- **Cache Coherence**: Version-based consistency and automatic promotion/demotion
- **Intelligent Eviction**: LRU with expiration and size-based policies
- **Asynchronous Operations**: Non-blocking cache operations with background maintenance

#### Advanced Database Optimization
- **QueryOptimizer**: Intelligent query caching with performance analysis
- **PreparedStatement Pooling**: Automatic statement reuse and optimization
- **Result Caching**: Smart caching of read-only query results with TTL
- **Slow Query Detection**: Automatic identification and logging of performance bottlenecks
- **Batch Processing**: Optimized batch execution with retry mechanisms
- **Query Analytics**: Comprehensive performance metrics and reporting

#### AI-Powered Performance Systems
- **LoadPredictionEngine**: Machine learning-based load prediction with linear regression and seasonal analysis
- **Predictive Scaling**: Automatic resource allocation based on predicted traffic patterns
- **Pattern Recognition**: Historical data analysis with 168-hour rolling windows
- **Anomaly Detection**: Intelligent identification of performance deviations

#### Intelligent Garbage Collection
- **IntelligentGarbageCollector**: Advanced GC monitoring with adaptive tuning
- **GC Performance Tracking**: Real-time collection metrics and pause time analysis
- **Memory Pool Optimization**: Intelligent heap management and generation tuning
- **Emergency GC Handling**: Automatic intervention during performance crises

#### Automatic Data Sharding
- **AutoShardingManager**: Dynamic data partitioning with automatic rebalancing
- **Shard Load Balancing**: Intelligent distribution based on access patterns
- **Hot Shard Detection**: Automatic identification and splitting of overloaded shards
- **Data Migration**: Seamless shard rebalancing without service interruption

#### Real-Time Performance Profiling
- **RealTimeProfiler**: Low-overhead performance monitoring with method-level granularity
- **Bottleneck Detection**: Automatic identification of slow methods and memory pressure
- **System Snapshots**: Comprehensive runtime state capture and analysis
- **Performance Recommendations**: AI-driven optimization suggestions

#### Advanced I/O Optimization
- **AsyncIOOptimizer**: High-performance file operations using NIO.2 asynchronous channels
- **Intelligent Buffering**: Adaptive buffer pooling with direct memory allocation
- **Batch Processing**: Optimized multi-file operations with chunked writes
- **NetworkIOOptimizer**: Advanced network I/O with packet batching and gathered writes
- **Connection Multiplexing**: Efficient client connection handling with async completion handlers

#### Latest Generation Performance Systems

#### Advanced Data Structures & Collections
- **AdvancedCollections**: Complete suite of high-performance data structures
- **AdaptiveConcurrentMap**: Self-optimizing concurrent maps with performance tracking
- **AdvancedRingBuffer**: High-performance ring buffer with overflow protection
- **OptimizedBloomFilter**: Probabilistic membership testing with entropy-based optimization
- **BatchingQueue**: Lock-free concurrent queue with intelligent batching capabilities
- **IntelligentStringInterner**: Memory-efficient string interning with LRU eviction

#### Next-Level System Monitoring
- **AdvancedMonitoringSystem**: Comprehensive real-time system monitoring
- **MetricsCollector**: JVM metrics collection (CPU, memory, threads, GC)
- **AlertingEngine**: Intelligent alerting with configurable thresholds and escalation
- **HealthChecker**: Automated system health diagnostics with actionable recommendations
- **PerformanceDashboard**: Live updating dashboard with formatted system status

#### Dynamic Compression Engine
- **DynamicCompressionEngine**: Intelligent compression with algorithm auto-selection
- **CompressionAlgorithmSelector**: Performance-based algorithm selection with machine learning
- **DataProfiler**: Real-time data analysis with entropy calculation and pattern detection
- **AdaptiveCompressionCache**: LRU-based caching with overflow protection
- **Multi-Algorithm Support**: DEFLATE, GZIP, LZ4, Snappy with asynchronous processing

#### Multi-Tier Load Balancing
- **MultiTierLoadBalancer**: Enterprise-grade load balancing across multiple tiers
- **TierManager**: Specialized management for Edge, Application, Database, and Cache tiers
- **RequestRouter**: Intelligent request routing with caching and optimization
- **PredictiveScaler**: Historical pattern analysis with automatic resource scaling
- **HealthMonitor**: Continuous server health monitoring with automatic failover

#### Custom Memory Management
- **CustomMemoryAllocators**: Advanced memory management with specialized allocators
- **DirectMemoryAllocator**: Pooled ByteBuffer allocation with size-based optimization
- **PooledObjectAllocator**: Type-aware object pooling with factory patterns
- **ArenaAllocator**: Bulk memory allocation with dedicated and shared strategies
- **SmallObjectAllocator**: Optimized allocation for frequent small objects
- **MemoryLeakDetector**: Phantom reference-based leak detection with stack trace analysis

#### Advanced Security Framework
- **AdvancedSecuritySystem**: Comprehensive security with AI-powered threat detection
- **ThreatDetectionEngine**: Pattern-based analysis for SQL injection, XSS, command injection
- **RateLimitingSystem**: Adaptive rate limiting with client-specific thresholds
- **AdvancedEncryptionManager**: AES-256 encryption with automatic key rotation
- **SessionSecurityManager**: Secure session management with timeout and validation
- **AntiDDoSProtection**: Traffic pattern analysis with automatic attack mitigation
- **SecurityAuditor**: Comprehensive event logging and real-time threat monitoring

### Configuration Changes
- Added `debug.sql` configuration option for production environments
- Enhanced MySQL connection parameters for security and performance
- Improved database pool sizing validation
- Configurable batch processing and cache TTL settings

## Development Notes

- Target Java 8 compatibility
- Uses Maven assembly plugin for building executable JARs
- No unit tests currently present in the codebase  
- Extensive use of singleton patterns for managers
- Event-driven architecture throughout the system
- **NEW**: Thread-safe collections used in all concurrent contexts
- **NEW**: Enhanced error handling and logging throughout the codebase