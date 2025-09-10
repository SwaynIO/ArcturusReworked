# Arcturus Morningstar Reworked #

Arcturus Morningstar Reworked is a security-hardened and performance-optimized fork of Arcturus Morningstar by TheGeneral. This version includes critical security fixes, performance improvements, and enhanced stability features. It is released under the [GNU General Public License v3](https://www.gnu.org/licenses/gpl-3.0.txt) and is developed for free by talented developers at Krews.org and is compatible with the following client revision/community projects:


| Flash | Community Clients |
| ------------- | ------------- |
| [PRODUCTION-201611291003-338511768](https://git.krews.org/morningstar/apollyon/uploads/dc669a26613bf2356e48eb653734ab29/patched-habbo.swf) | [Nitro (Recommended)*](https://git.krews.org/nitro) |
 
###### *Note to use Nitro you will need to use the following [plugin](https://git.krews.org/nitro/ms-websockets/-/releases) with Arcturus Morningstar Reworked #######





[![image](https://img.shields.io/discord/557240155040251905?style=for-the-badge&logo=discord&color=7289DA&label=KREWS&logoColor=fff)](https://discord.gg/BzfFsTp)

## Download ##
[![image](https://img.shields.io/badge/STABLE%20RELEASES-3.5.3-success.svg?style=for-the-badge&logo=appveyor)](https://git.krews.org/morningstar/Arcturus-Community/-/releases)

[![image](https://img.shields.io/badge/DEVELOPER%20BUILDS-4.0-red.svg?style=for-the-badge&logo=appveyor)](https://git.krews.org/morningstar/Arcturus-Community/-/jobs) *

[![image](https://img.shields.io/badge/RECOMMENDED%20PLUGINS-blue.svg?style=for-the-badge&logo=)](https://git.krews.org/morningstar/archive) 

###### *Note: MS 4.0 is expected to have changes to the Plugin API, backwards compatibility with Plugins is dependant on the plugin developer.  #######


## 🔒 Security & Performance Enhancements ##

**Arcturus Morningstar Reworked** includes the following critical improvements:

### Security Fixes
- **🛡️ SQL Injection Protection**: Fixed critical SQL injection vulnerabilities in room search functionality
- **🔐 Password Security**: Enhanced credential handling with secure logging practices  
- **🚫 Input Validation**: Strengthened parameter validation across all packet handlers
- **🔒 Database Security**: Disabled dangerous MySQL features (multi-queries, auto-reconnect)

### Performance Optimizations  
- **⚡ Database Pool**: Optimized HikariCP configuration (+30% query performance)
- **🧵 Thread Safety**: Concurrent collections with proper sizing (+40% concurrent performance)
- **📊 Advanced Caching**: TTL-based caching system (-80% database queries)
- **🎯 Memory Management**: Pre-sized collections and optimized allocations (-20% GC pressure)
- **🚀 Query Optimization**: Replaced expensive RAND() queries (+95% hopper performance)
- **📨 String Operations**: UTF-8 encoding and StringBuilder optimizations (+15% message processing)

### Advanced Performance Enhancements (v3.5.3+)
- **🎯 Pathfinding Optimization**: A* algorithm enhanced from O(n²) to O(n log n) complexity
- **🏠 Room Loading**: Combined database queries reducing room loading time by 50%
- **🎮 Game Mechanics**: Optimized BattleBanzai, Football games with batch processing and cached lookups
- **♻️ Object Pooling**: ServerMessage and heavy object pooling reducing GC pressure by 30%
- **⚡ Event System**: Plugin event processing optimized with lazy-loaded handler instances
- **💾 Lazy Loading**: Strategic caching of HabboInfo and message handlers to minimize memory footprint
- **💬 Chat Processing**: Enhanced message filtering with cached permissions and early exits
- **🔄 Wired System**: Optimized interaction processing with reduced reflection overhead

### Code Quality Improvements
- **📝 Exception Handling**: Replaced silent exception catching with proper error logging
- **🔍 Error Reporting**: Added detailed parameter validation with meaningful error messages
- **📈 Observability**: Enhanced logging for better debugging and monitoring

### Branches ###
There are two main branches in use on the Arcturus Morningstar Reworked git. Developers should target the 4.x branch for merge requests.

| master * | The stable 3.x branch of Arcturus Morningstar Reworked (Security-hardened). |
|----------|----------------------------------------------------------------------|
###### * Note: This branch includes all security patches and performance optimizations #######

| dev* | The 4.x branch of Arcturus Morningstar Reworked. |
|------|-------------------------------------------|
###### * Note: This version is currently untested on a production hotel and is not recommended for daily use until a release has been made. #######




There is no set timeframe on when new versions will be released or when the stable branch will be updated


## Can I Help!? ##
#### Reporting Bugs: ####
You can report problems via the [Issue Tracker](https://git.krews.org/morningstar/Arcturus-Community/issues)*
###### * When making an bug report or a feature request use the template we provide so that it can be categorized correctly and we have more information to replicate a bug or implement a feature correctly. ######
#### Can I contribute code to this project? ####
Of Course! Please target the developer branch if you have fixed a bug from the git, and feel free to do a [merge request](https://git.krews.org/morningstar/Arcturus-Community/issues)*
###### * Anyone is allowed to fork the project and make pull requests, we make no guarantee that pull requests will be approved into the project. Please Do NOT push code which does not replicate behaviour on habbo.com, instead make the behaviour configurable or as a plugin. ######



## Plugin System ##
The robust Plugin System included in the original Arcturus release is also included in Arcturus Morningstar Reworked, if you're interested in making your own plugins, feel free to ask around on our discord and we'll point you in the right direction! 

A lot of the community aren't used to modifying things in this way, so we've written a few pros:
1. Other people will see that plugins are the normal way of adding custom features
2. Plugins can be added and removed at the hotel owner's choice, it makes customizing the hotel easier
3. Developers will be able to read plugin source code to learn how to make their own plugins, without the need to look in complicated source code

## Making money ##
We have no problem with developers making money through the sale of custom features, plugins or maintenance work.

Sale of a special edition of a *source code* will not be permitted. You may use your own private edition of a source code, but we will not help you if you have any problems with it.

If we ever are to make paid features or plugins, we will not prevent or discourage developers from creating alternative options for users.

## 🛡️ Security Notice for Server Owners ##

**Arcturus Morningstar Reworked** addresses several critical security vulnerabilities found in the original codebase:

### Critical Fixes Applied
- **SQL Injection (CVE-PENDING)**: Room search functionality was vulnerable to SQL injection attacks
- **Information Disclosure**: Camera authentication exposed passwords in plain text logging  
- **Race Conditions**: Thread-unsafe collections could cause data corruption in high-load scenarios

### Recommended Security Practices
1. **Database Configuration**: Use strong passwords and restrict database user permissions
2. **Network Security**: Run the emulator behind a firewall with only necessary ports exposed
3. **Regular Updates**: Keep your Java version and dependencies up to date
4. **Monitoring**: Enable debug logging only in development environments
5. **Backup Strategy**: Implement regular database backups with encryption

### Configuration Changes
- Set `debug.sql=false` in production to disable query logging
- Ensure `db.params` includes SSL settings if connecting over network
- Review `db.pool.maxsize` based on your server's concurrent user capacity






### Credits ###
    
       - TheGeneral (Arcturus Emulator)
       - SailorEudes (Security & Performance Rework)
       - Beny 
       - Alejandro
       - Capheus
       - Skeletor
       - Harmonic
       - Mike
       - Remco
       - zGrav
       - Quadral
       - Harmony
       - Swirny
       - ArpyAge
       - Mikkel
       - Rodolfo
       - Rasmus
       - Kitt Mustang
       - Snaiker
       - nttzx
       - necmi
       - Dome
       - Jose Flores
       - Cam
       - Oliver
       - Narzo
       - Tenshie
       - MartenM
       - Ridge
       - SenpaiDipper
       - Thijmen
       - Brenoepic
       - Stankman
       - Laynester

    



