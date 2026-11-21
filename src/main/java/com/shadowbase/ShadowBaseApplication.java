package com.shadowbase;

import com.shadowbase.config.DatabaseConfig;
import com.shadowbase.migration.BackfillWorker;
import com.shadowbase.migration.ShadowTableService;
import com.shadowbase.migration.TriggerGeneratorService;
import com.shadowbase.model.MigrationRequest;
import com.shadowbase.parser.DdlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

public final class ShadowBaseApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShadowBaseApplication.class);

    private ShadowBaseApplication() {
    }

    public static void main(String[] args) throws SQLException, InterruptedException {
        try (DatabaseConfig databaseConfig = new DatabaseConfig()) {
            if (!databaseConfig.testConnection()) {
                throw new SQLException("Database health check failed");
            }
            LOGGER.info("ShadowBase sandbox startup ready (migration batch size: {})",
                    databaseConfig.getMigrationBatchSize());
            if (args.length > 0) {
                if (!"--provision".equals(args[0]) || args.length < 2) {
                    throw new IllegalArgumentException(
                            "Usage: ShadowBaseApplication --provision \"ALTER TABLE ... ADD COLUMN ...\"");
                }
                provisionShadowTable(databaseConfig, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
            }
        }
    }

    private static void provisionShadowTable(DatabaseConfig databaseConfig, String alterSql)
            throws SQLException, InterruptedException {
        MigrationRequest request = DdlParser.parse(alterSql);
        LOGGER.info("Parsed ALTER TABLE statement for {}", request.tableName());
        try (Connection connection = databaseConfig.getConnection()) {
            new ShadowTableService().provision(connection, request);
            TriggerGeneratorService triggerGeneratorService = new TriggerGeneratorService();
            List<String> sharedColumns = triggerGeneratorService.getLiveTableColumns(connection, request);
            triggerGeneratorService.createDualWriteTriggers(connection, request, sharedColumns);
            new BackfillWorker().backfill(connection, request, sharedColumns,
                    databaseConfig.getMigrationBatchSize());
        }
    }
}
