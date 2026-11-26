package com.shadowbase.migration;

import com.shadowbase.config.DatabaseConfig;
import com.shadowbase.metrics.MigrationMetrics;
import com.shadowbase.model.MigrationRequest;
import com.shadowbase.parser.DdlParser;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class CutoverService {
    private static final String LOCK_TIMEOUT = "SET LOCAL lock_timeout = '2s'";
    private final DatabaseConfig databaseConfig;

    public CutoverService() {
        this.databaseConfig = null;
    }

    public CutoverService(DatabaseConfig databaseConfig) {
        this.databaseConfig = Objects.requireNonNull(databaseConfig,
                "databaseConfig must not be null");
    }

    public String cutover(Connection connection, MigrationRequest request,
                          MigrationMetrics metrics) throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(metrics, "metrics must not be null");
        MigrationRequest validatedRequest = validateRequest(request);
        String liveTable = validatedRequest.tableName();
        String shadowTable = validatedRequest.shadowTableName();
        String archivedTable = withTablePrefix(liveTable, "_old_");

        AtomicReference<SQLException> cutoverFailure = new AtomicReference<>();
        metrics.measureCutoverLock(() -> {
            try {
                executeTransaction(connection, () -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(LOCK_TIMEOUT);
                        statement.execute("LOCK TABLE " + liveTable + ", " + shadowTable
                                + " IN ACCESS EXCLUSIVE MODE");
                    }
                    new TriggerGeneratorService().dropDualWriteTriggers(connection,
                            validatedRequest);
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("ALTER TABLE " + liveTable + " RENAME TO "
                                + lastIdentifier(archivedTable));
                        statement.execute("ALTER TABLE " + shadowTable + " RENAME TO "
                                + lastIdentifier(liveTable));
                    }
                    return null;
                });
            } catch (SQLException failure) {
                cutoverFailure.set(failure);
            }
        });
        if (cutoverFailure.get() != null) {
            throw cutoverFailure.get();
        }
        return archivedTable;
    }

    public String rollbackCutover(String liveTableName, String archivedOldTableName)
            throws SQLException {
        if (databaseConfig == null) {
            throw new IllegalStateException(
                    "A DatabaseConfig is required to rollback a cutover by table name");
        }
        try (Connection connection = databaseConfig.getConnection()) {
            return rollbackCutover(connection, liveTableName, archivedOldTableName);
        }
    }

    public String rollbackCutover(Connection connection, String liveTableName,
                                  String archivedOldTableName) throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        String liveTable = validateTableName(liveTableName);
        String archivedTable = validateTableName(archivedOldTableName);
        requireSameSchema(liveTable, archivedTable);
        String displacedTable = withTablePrefix(liveTable, "_failed_");

        executeTransaction(connection, () -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute(LOCK_TIMEOUT);
                statement.execute("LOCK TABLE " + liveTable + ", " + archivedTable
                        + " IN ACCESS EXCLUSIVE MODE");
                statement.execute("ALTER TABLE " + liveTable + " RENAME TO "
                        + lastIdentifier(displacedTable));
                statement.execute("ALTER TABLE " + archivedTable + " RENAME TO "
                        + lastIdentifier(liveTable));
            }
            return null;
        });
        return displacedTable;
    }

    private static MigrationRequest validateRequest(MigrationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        MigrationRequest parsedRequest = DdlParser.parse(request.rawSql());
        if (!parsedRequest.equals(request)) {
            throw new IllegalArgumentException("MigrationRequest fields must match its parsed SQL");
        }
        return parsedRequest;
    }

    private static String validateTableName(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("Table names must not be null or blank");
        }
        return DdlParser.parse("ALTER TABLE " + tableName
                + " ADD COLUMN _shadowbase_validation INTEGER").tableName();
    }

    private static String withTablePrefix(String tableName, String prefix) {
        int separator = lastSeparator(tableName);
        String qualifier = tableName.substring(0, separator + 1);
        String identifier = tableName.substring(separator + 1);
        if (identifier.startsWith("\"") && identifier.endsWith("\"")) {
            String unquoted = identifier.substring(1, identifier.length() - 1)
                    .replace("\"\"", "\"");
            return qualifier + quoteIdentifier(prefix + unquoted);
        }
        return qualifier + prefix + identifier;
    }

    private static String lastIdentifier(String qualifiedTableName) {
        return qualifiedTableName.substring(lastSeparator(qualifiedTableName) + 1);
    }

    private static int lastSeparator(String tableName) {
        boolean inQuotedIdentifier = false;
        int separator = -1;
        for (int index = 0; index < tableName.length(); index++) {
            char current = tableName.charAt(index);
            if (current == '"') {
                if (inQuotedIdentifier && index + 1 < tableName.length()
                        && tableName.charAt(index + 1) == '"') {
                    index++;
                } else {
                    inQuotedIdentifier = !inQuotedIdentifier;
                }
            } else if (current == '.' && !inQuotedIdentifier) {
                separator = index;
            }
        }
        return separator;
    }

    private static void requireSameSchema(String firstTable, String secondTable) {
        int firstSeparator = lastSeparator(firstTable);
        int secondSeparator = lastSeparator(secondTable);
        String firstSchema = firstTable.substring(0, firstSeparator + 1);
        String secondSchema = secondTable.substring(0, secondSeparator + 1);
        if (!firstSchema.equals(secondSchema)) {
            throw new IllegalArgumentException("Live and archived tables must use the same schema");
        }
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static <T> T executeTransaction(Connection connection, SqlOperation<T> operation)
            throws SQLException {
        if (!connection.getAutoCommit()) {
            throw new IllegalStateException("Cutover requires a connection in auto-commit mode");
        }
        connection.setAutoCommit(false);
        Throwable primaryFailure = null;
        try {
            T result = operation.execute();
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException failure) {
            primaryFailure = failure;
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException restoreFailure) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(restoreFailure);
                } else {
                    throw restoreFailure;
                }
            }
        }
    }

    @FunctionalInterface
    private interface SqlOperation<T> {
        T execute() throws SQLException;
    }
}
