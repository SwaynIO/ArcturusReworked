package com.eu.habbo.database;

import com.eu.habbo.Emulator;
import com.eu.habbo.core.ConfigurationManager;
import com.zaxxer.hikari.HikariDataSource;
import gnu.trove.map.hash.THashMap;
import gnu.trove.set.hash.THashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Database {

    private static final Logger LOGGER = LoggerFactory.getLogger(Database.class);

    private HikariDataSource dataSource;
    private DatabasePool databasePool;

    public Database(ConfigurationManager config) {
        long millis = System.currentTimeMillis();

        boolean SQLException = false;

        try {
            this.databasePool = new DatabasePool();
            if (!this.databasePool.getStoragePooling(config)) {
                LOGGER.info("Failed to connect to the database. Please check config.ini and make sure the MySQL process is running. Shutting down...");
                SQLException = true;
                return;
            }
            this.dataSource = this.databasePool.getDatabase();
        } catch (Exception e) {
            SQLException = true;
            LOGGER.error("Failed to connect to your database.", e);
        } finally {
            if (SQLException) {
                Emulator.prepareShutdown();
            }
        }

        LOGGER.info("Database -> Connected! ({} MS)", System.currentTimeMillis() - millis);
    }

    public void dispose() {
        if (this.databasePool != null) {
            this.databasePool.getDatabase().close();
        }

        this.dataSource.close();
    }

    public HikariDataSource getDataSource() {
        return this.dataSource;
    }

    public DatabasePool getDatabasePool() {
        return this.databasePool;
    }

    // Cache compiled patterns for better performance
    private static final Map<String, Pattern> PATTERN_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    
    public static PreparedStatement preparedStatementWithParams(Connection connection, String query, THashMap<String, Object> queryParams) throws SQLException {
        THashMap<Integer, Object> params = new THashMap<>();
        THashSet<String> quotedParams = new THashSet<>(queryParams.size());

        // Pre-allocate with known size for better performance
        for(String key : queryParams.keySet()) {
            quotedParams.add(Pattern.quote(key));
        }

        String regex = "(" + String.join("|", quotedParams) + ")";
        
        // Use cached pattern for better performance
        Pattern pattern = PATTERN_CACHE.computeIfAbsent(regex, Pattern::compile);
        Matcher m = pattern.matcher(query);

        int i = 1;
        while (m.find()) {
            try {
                String paramName = m.group(1);
                if (queryParams.containsKey(paramName)) {
                    params.put(i, queryParams.get(paramName));
                    i++;
                } else {
                    throw new IllegalArgumentException("Missing query parameter: " + paramName);
                }
            }
            catch (IllegalArgumentException e) {
                throw e; // Re-throw parameter errors
            }
            catch (Exception e) {
                throw new RuntimeException("Error processing query parameters", e);
            }
        }

        PreparedStatement statement = connection.prepareStatement(query.replaceAll(regex, "?"));

        // Optimized parameter setting using instanceof for better performance
        for(Map.Entry<Integer, Object> entry : params.entrySet()) {
            Object value = entry.getValue();
            int index = entry.getKey();
            
            if (value instanceof String) {
                statement.setString(index, (String) value);
            } else if (value instanceof Integer) {
                statement.setInt(index, (Integer) value);
            } else if (value instanceof Long) {
                statement.setLong(index, (Long) value);
            } else if (value instanceof Double) {
                statement.setDouble(index, (Double) value);
            } else if (value instanceof Float) {
                statement.setFloat(index, (Float) value);
            } else if (value instanceof Boolean) {
                statement.setBoolean(index, (Boolean) value);
            } else {
                statement.setObject(index, value);
            }
        }

        return statement;
    }
}
