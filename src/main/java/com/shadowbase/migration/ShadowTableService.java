package com.shadowbase.migration;

import com.shadowbase.model.MigrationRequest;
import com.shadowbase.parser.DdlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.Objects;

public final class ShadowTableService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ShadowTableService.class);

    public void provision(Connection connection, MigrationRequest request) throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        MigrationRequest validatedRequest = validateRequest(request);
        Savepoint savepoint = connection.getAutoCommit() ? null : connection.setSavepoint();

        try (Statement statement = connection.createStatement()) {
            execute(statement, "DROP TABLE IF EXISTS " + validatedRequest.shadowTableName(),
                    "Drop leftover shadow table");
            execute(statement, "CREATE TABLE " + validatedRequest.shadowTableName()
                    + " (LIKE " + validatedRequest.tableName() + " INCLUDING ALL)",
                    "Clone live table schema");
            execute(statement, "ALTER TABLE " + validatedRequest.shadowTableName() + " "
                    + validatedRequest.alterClause(), "Apply target schema alteration");

            if (savepoint != null) {
                connection.releaseSavepoint(savepoint);
            }
            LOGGER.info("Shadow table {} provisioned successfully", validatedRequest.shadowTableName());
        } catch (SQLException failure) {
            LOGGER.error("Shadow table provisioning failed for {}",
                    validatedRequest.shadowTableName(), failure);
            rollbackToSavepoint(connection, savepoint, failure);
            cleanupShadowTable(connection, validatedRequest.shadowTableName(), failure);
            throw failure;
        }
    }

    private static MigrationRequest validateRequest(MigrationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        MigrationRequest parsedRequest = DdlParser.parse(request.rawSql());
        if (!parsedRequest.equals(request)) {
            throw new IllegalArgumentException("MigrationRequest fields must match its parsed SQL");
        }
        return parsedRequest;
    }

    private static void execute(Statement statement, String sql, String step) throws SQLException {
        LOGGER.info("{}: {}", step, sql);
        statement.execute(sql);
    }

    private static void rollbackToSavepoint(Connection connection, Savepoint savepoint,
                                            SQLException failure) {
        if (savepoint == null) {
            return;
        }
        try {
            connection.rollback(savepoint);
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            LOGGER.error("Could not roll back failed shadow table provisioning to its savepoint",
                    rollbackFailure);
        }
    }

    private static void cleanupShadowTable(Connection connection, String shadowTableName,
                                           SQLException failure) {
        String cleanupSql = "DROP TABLE IF EXISTS " + shadowTableName;
        try (Statement cleanupStatement = connection.createStatement()) {
            LOGGER.warn("Cleaning up failed shadow table with: {}", cleanupSql);
            cleanupStatement.execute(cleanupSql);
        } catch (SQLException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
            LOGGER.error("Could not clean up shadow table {}", shadowTableName, cleanupFailure);
        }
    }
}
