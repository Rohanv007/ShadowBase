package com.shadowbase;

import com.shadowbase.config.DatabaseConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

public final class ShadowBaseApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShadowBaseApplication.class);

    private ShadowBaseApplication() {
    }

    public static void main(String[] args) throws SQLException {
        try (DatabaseConfig databaseConfig = new DatabaseConfig()) {
            if (!databaseConfig.testConnection()) {
                throw new SQLException("Database health check failed");
            }
            LOGGER.info("ShadowBase sandbox startup ready (migration batch size: {})",
                    databaseConfig.getMigrationBatchSize());
        }
    }
}
