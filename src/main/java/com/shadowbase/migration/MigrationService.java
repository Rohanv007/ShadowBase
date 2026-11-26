package com.shadowbase.migration;

import com.shadowbase.config.DatabaseConfig;
import com.shadowbase.metrics.MigrationMetrics;
import com.shadowbase.metrics.MigrationPhase;
import com.shadowbase.model.MigrationRequest;
import com.shadowbase.parser.DdlParser;
import com.shadowbase.verification.ConsistencyVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

public final class MigrationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationService.class);
    private final DatabaseConfig databaseConfig;
    private final int batchSize;

    public MigrationService(DatabaseConfig databaseConfig) {
        this(databaseConfig, Objects.requireNonNull(databaseConfig,
                "databaseConfig must not be null").getMigrationBatchSize());
    }

    public MigrationService(DatabaseConfig databaseConfig, int batchSize) {
        this.databaseConfig = Objects.requireNonNull(databaseConfig,
                "databaseConfig must not be null");
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be a positive integer");
        }
        this.batchSize = batchSize;
    }

    public MigrationResult migrate(String alterSql) throws SQLException, InterruptedException {
        MigrationMetrics metrics = new MigrationMetrics();
        try {
            return migrate(alterSql, metrics);
        } finally {
            LOGGER.info("\n{}", metrics.formatSummaryReport());
        }
    }

    public MigrationResult migrate(String alterSql, MigrationMetrics metrics)
            throws SQLException, InterruptedException {
        return migrate(alterSql, metrics, false);
    }

    public MigrationResult migrateWithCutover(String alterSql, MigrationMetrics metrics)
            throws SQLException, InterruptedException {
        return migrate(alterSql, metrics, true);
    }

    private MigrationResult migrate(String alterSql, MigrationMetrics metrics, boolean cutover)
            throws SQLException, InterruptedException {
        Objects.requireNonNull(metrics, "metrics must not be null");
        long startNanos = System.nanoTime();

        try {
            MigrationRequest request = DdlParser.parse(alterSql);
            metrics.transitionTo(MigrationPhase.PROVISIONING);
            MigrationResult result;
            try (Connection connection = databaseConfig.getConnection()) {
                new ShadowTableService().provision(connection, request);
                TriggerGeneratorService triggerGenerator = new TriggerGeneratorService();
                List<String> sharedColumns = triggerGenerator.getLiveTableColumns(connection, request);
                metrics.transitionTo(MigrationPhase.TRIGGER_SYNC);
                triggerGenerator.createDualWriteTriggers(connection, request, sharedColumns);
                metrics.transitionTo(MigrationPhase.BACKFILLING);
                BackfillWorker.BackfillProgress progress = new BackfillWorker().backfill(
                        connection, request, sharedColumns, batchSize);
                metrics.addBackfilledRows(progress.totalRowsCopied());
                metrics.addProcessedBatches(progress.batchCount());
                metrics.transitionTo(MigrationPhase.VERIFYING);
                ConsistencyVerifier.VerificationResult verification =
                        new ConsistencyVerifier().inspect(
                                connection, request, sharedColumns, batchSize);
                metrics.addVerificationMismatches(verification.mismatchedRowIds().size());
                if (!verification.isConsistent()) {
                    throw new IllegalStateException(
                            "Data consistency verification failed: " + verification);
                }
                LOGGER.info("Data consistency verification passed for {} -> {}: "
                                + "liveRows={}, shadowRows={}",
                        request.tableName(), request.shadowTableName(),
                        verification.liveRowCount(), verification.shadowRowCount());
                if (cutover) {
                    new CutoverService().cutover(connection, request, metrics);
                }

                result = new MigrationResult(
                        request.tableName(), request.shadowTableName(),
                        progress.totalRowsCopied(), progress.batchCount(),
                        verification.isConsistent(),
                        (System.nanoTime() - startNanos) / 1_000_000);
            }
            metrics.complete();
            return result;
        } catch (SQLException | InterruptedException | RuntimeException failure) {
            metrics.fail();
            throw failure;
        }
    }

    public record MigrationResult(String tableName, String shadowTableName, long rowsCopied,
                                  long batches, boolean consistent, long elapsedMillis) {
    }
}
