package com.shadowbase;

import com.shadowbase.config.DatabaseConfig;
import com.shadowbase.metrics.MigrationMetrics;
import com.shadowbase.migration.MigrationService;
import com.shadowbase.parser.DdlParser;
import com.shadowbase.simulation.TrafficSimulator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

public final class ShadowBaseApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShadowBaseApplication.class);

    private ShadowBaseApplication() {
    }

    public static void main(String[] args)
            throws IOException, SQLException, InterruptedException {
        try (DatabaseConfig databaseConfig = new DatabaseConfig()) {
            if (!databaseConfig.testConnection()) {
                throw new SQLException("Database health check failed");
            }
            LOGGER.info("ShadowBase sandbox startup ready (migration batch size: {})",
                    databaseConfig.getMigrationBatchSize());
            if (args.length > 0) {
                if ("--server".equals(args[0])) {
                    startServer(databaseConfig, args);
                } else if (("--provision".equals(args[0]) || "--migrate".equals(args[0]))
                        && args.length >= 2) {
                    migrate(databaseConfig, String.join(" ",
                            Arrays.copyOfRange(args, 1, args.length)));
                } else if ("--simulate".equals(args[0]) && args.length >= 4) {
                    simulateMigration(databaseConfig, parsePositiveInt(args[1], "writesPerSecond"),
                            parsePositiveInt(args[2], "workerCount"),
                            String.join(" ", Arrays.copyOfRange(args, 3, args.length)));
                } else {
                    throw new IllegalArgumentException(
                            "Usage: ShadowBaseApplication --migrate \"ALTER TABLE ... ADD COLUMN ...\""
                                    + " | --simulate <writesPerSecond> <workerCount> "
                                    + "\"ALTER TABLE ... ADD COLUMN ...\" | --server [port]");
                }
            }
        }
    }

    private static MigrationService.MigrationResult migrate(
            DatabaseConfig databaseConfig, String alterSql)
            throws SQLException, InterruptedException {
        MigrationMetrics metrics = new MigrationMetrics();
        try {
            return runMigration(databaseConfig, alterSql, metrics);
        } finally {
            LOGGER.info("\n{}", metrics.formatSummaryReport());
        }
    }

    private static MigrationService.MigrationResult simulateMigration(
            DatabaseConfig databaseConfig, int writesPerSecond, int workerCount, String alterSql)
            throws SQLException, InterruptedException {
        MigrationMetrics metrics = new MigrationMetrics();
        String tableName = DdlParser.parse(alterSql).tableName();
        try (TrafficSimulator simulator = new TrafficSimulator(databaseConfig, metrics)) {
            simulator.startSimulation(tableName, writesPerSecond, workerCount);
            return runMigration(databaseConfig, alterSql, metrics);
        } finally {
            LOGGER.info("\n{}", metrics.formatSummaryReport());
        }
    }

    private static MigrationService.MigrationResult runMigration(
            DatabaseConfig databaseConfig, String alterSql, MigrationMetrics metrics)
            throws SQLException, InterruptedException {
        MigrationService.MigrationResult result =
                new MigrationService(databaseConfig).migrate(alterSql, metrics);
        LOGGER.info("Migration completed for {} -> {}: rowsCopied={}, batches={}, "
                        + "consistent={}, elapsedMs={}",
                result.tableName(), result.shadowTableName(), result.rowsCopied(),
                result.batches(), result.consistent(), result.elapsedMillis());
        return result;
    }

    private static void startServer(DatabaseConfig databaseConfig, String[] args)
            throws IOException, InterruptedException {
        if (args.length > 2) {
            throw new IllegalArgumentException("Usage: ShadowBaseApplication --server [port]");
        }
        int port = args.length == 2 ? parsePort(args[1]) : 8080;
        CountDownLatch shutdown = new CountDownLatch(1);
        try (MigrationHttpServer server = new MigrationHttpServer(
                alterSql -> migrate(databaseConfig, alterSql), port)) {
            Runtime.getRuntime().addShutdownHook(new Thread(shutdown::countDown));
            server.start();
            shutdown.await();
        }
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port >= 0 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Port must be an integer from 0 to 65535", exception);
        }
        throw new IllegalArgumentException("Port must be an integer from 0 to 65535");
    }

    private static int parsePositiveInt(String value, String parameterName) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(parameterName + " must be a positive integer",
                    exception);
        }
        throw new IllegalArgumentException(parameterName + " must be a positive integer");
    }
}
