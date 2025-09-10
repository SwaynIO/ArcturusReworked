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

**Arcturus Morningstar Reworked** includes critical security fixes and performance optimizations:

### Security Improvements
- **SQL Injection Protection**: Fixed parameterized queries in `RoomManager.findRooms()`
- **Password Security**: Enhanced credential handling in `CameraLoginComposer`
- **Input Validation**: Strengthened parameter validation across packet handlers
- **Thread Safety**: Replaced non-thread-safe collections with concurrent alternatives

### Performance Optimizations
- **Database Pool**: Optimized HikariCP configuration with better caching and timeouts
- **Connection Management**: Reduced connection timeout from 300s to 30s
- **Prepared Statement Cache**: Increased from 500 to 1000 statements
- **Exception Handling**: Replaced silent catches with proper error logging

### Configuration Changes
- Added `debug.sql` configuration option for production environments
- Enhanced MySQL connection parameters for security and performance
- Improved database pool sizing validation

## Development Notes

- Target Java 8 compatibility
- Uses Maven assembly plugin for building executable JARs
- No unit tests currently present in the codebase  
- Extensive use of singleton patterns for managers
- Event-driven architecture throughout the system
- **NEW**: Thread-safe collections used in all concurrent contexts
- **NEW**: Enhanced error handling and logging throughout the codebase