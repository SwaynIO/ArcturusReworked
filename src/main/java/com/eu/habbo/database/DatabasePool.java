package com.eu.habbo.database;

import com.eu.habbo.core.ConfigurationManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class DatabasePool {
    private final Logger log = LoggerFactory.getLogger(DatabasePool.class);
    private HikariDataSource database;

    public boolean getStoragePooling(ConfigurationManager config) {
        try {
            HikariConfig databaseConfiguration = new HikariConfig();
            // Optimized pool configuration for better performance
            int maxPoolSize = config.getInt("db.pool.maxsize", 50);
            int minIdle = config.getInt("db.pool.minsize", 10);
            
            // Ensure sensible defaults
            if (maxPoolSize < minIdle) maxPoolSize = minIdle * 2;
            if (minIdle > maxPoolSize) minIdle = maxPoolSize / 2;
            
            databaseConfiguration.setMaximumPoolSize(maxPoolSize);
            databaseConfiguration.setMinimumIdle(minIdle);
            
            String jdbcUrl = "jdbc:mysql://" + config.getValue("db.hostname", "localhost") 
                + ":" + config.getValue("db.port", "3306") 
                + "/" + config.getValue("db.database", "habbo") 
                + config.getValue("db.params", "?useSSL=false&allowPublicKeyRetrieval=true");
                
            databaseConfiguration.setJdbcUrl(jdbcUrl);
            databaseConfiguration.addDataSourceProperty("user", config.getValue("db.username"));
            databaseConfiguration.addDataSourceProperty("password", config.getValue("db.password"));
            
            // Performance optimizations
            databaseConfiguration.addDataSourceProperty("prepStmtCacheSize", "1000");
            databaseConfiguration.addDataSourceProperty("prepStmtCacheSqlLimit", "4096");
            databaseConfiguration.addDataSourceProperty("cachePrepStmts", "true");
            databaseConfiguration.addDataSourceProperty("useServerPrepStmts", "true");
            databaseConfiguration.addDataSourceProperty("rewriteBatchedStatements", "true");
            databaseConfiguration.addDataSourceProperty("useUnicode", "true");
            databaseConfiguration.addDataSourceProperty("characterEncoding", "UTF-8");
            databaseConfiguration.addDataSourceProperty("useLocalSessionState", "true");
            databaseConfiguration.addDataSourceProperty("useLocalTransactionState", "true");
            databaseConfiguration.addDataSourceProperty("maintainTimeStats", "false");
            
            // Security and reliability
            databaseConfiguration.addDataSourceProperty("allowMultiQueries", "false");
            databaseConfiguration.addDataSourceProperty("autoReconnect", "false");
            
            // Logging (only in debug mode)
            boolean debugMode = config.getBoolean("debug.sql", false);
            if (debugMode) {
                databaseConfiguration.addDataSourceProperty("dataSource.logSlowQueries", "true");
                databaseConfiguration.addDataSourceProperty("dataSource.dumpQueriesOnException", "true");
            }
            
            databaseConfiguration.setAutoCommit(true);
            databaseConfiguration.setConnectionTimeout(30000L);  // Reduced from 300s to 30s
            databaseConfiguration.setValidationTimeout(3000L);   // Reduced from 5s to 3s
            databaseConfiguration.setLeakDetectionThreshold(60000L); // 1 minute
            databaseConfiguration.setMaxLifetime(1800000L);      // 30 minutes
            databaseConfiguration.setIdleTimeout(300000L);       // 5 minutes (reduced from 10)
            //databaseConfiguration.setDriverClassName("com.mysql.jdbc.jdbc2.optional.MysqlDataSource");
            this.database = new HikariDataSource(databaseConfiguration);
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    public HikariDataSource getDatabase() {
        return this.database;
    }
}