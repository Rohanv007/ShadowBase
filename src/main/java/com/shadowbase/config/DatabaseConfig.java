package com.shadowbase.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;

public final class DatabaseConfig implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseConfig.class);
    private static final String DEFAULT_DATABASE_URL = "jdbc:postgresql://localhost:5432/shadowbase";

    private final HikariDataSource dataSource;
    private final int migrationBatchSize;

    public DatabaseConfig() {
        Properties properties = loadProperties();
        String databaseUrl = getValue(properties, "db.url", "DB_URL", DEFAULT_DATABASE_URL);
        String username = getRequiredValue(properties, "db.username", "DB_USERNAME");
        String password = getRequiredValue(properties, "db.password", "DB_PASSWORD");
        migrationBatchSize = parseBatchSize(
                getValue(properties, "migration.batch.size", "MIGRATION_BATCH_SIZE", "1000"));

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(databaseUrl);
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        hikariConfig.setPoolName("shadowbase-pool");
        dataSource = new HikariDataSource(hikariConfig);
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public boolean testConnection() throws SQLException {
        try (Connection connection = getConnection()) {
            boolean valid = connection.isValid(5);
            if (valid) {
                LOGGER.info("Database connection health check succeeded");
            } else {
                LOGGER.warn("Database connection health check failed");
            }
            return valid;
        }
    }

    public int getMigrationBatchSize() {
        return migrationBatchSize;
    }

    @Override
    public void close() {
        dataSource.close();
    }

    private static Properties loadProperties() {
        Properties properties = new Properties();
        try (InputStream input = DatabaseConfig.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load application.properties", exception);
        }
        return properties;
    }

    private static String getValue(Properties properties, String propertyName, String environmentName,
                                   String defaultValue) {
        String environmentValue = System.getenv(environmentName);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue.trim();
        }

        String propertyValue = properties.getProperty(propertyName);
        return propertyValue == null || propertyValue.isBlank() ? defaultValue : propertyValue.trim();
    }

    private static String getRequiredValue(Properties properties, String propertyName, String environmentName) {
        String value = getValue(properties, propertyName, environmentName, null);
        if (value == null) {
            throw new IllegalStateException("Set " + propertyName + " in application.properties or "
                    + environmentName + " in the environment");
        }
        return value;
    }

    private static int parseBatchSize(String value) {
        try {
            int batchSize = Integer.parseInt(value);
            if (batchSize > 0) {
                return batchSize;
            }
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("migration.batch.size must be a positive integer", exception);
        }
        throw new IllegalStateException("migration.batch.size must be a positive integer");
    }
}
