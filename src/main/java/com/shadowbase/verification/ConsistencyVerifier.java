package com.shadowbase.verification;

import com.shadowbase.model.MigrationRequest;
import com.shadowbase.parser.DdlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class ConsistencyVerifier {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConsistencyVerifier.class);
    private static final long INITIAL_LAST_VERIFIED_ID = Long.MIN_VALUE;

    public VerificationResult verify(Connection connection, MigrationRequest request,
                                     List<String> sharedColumnNames, int batchSize)
            throws SQLException {
        VerificationResult result = inspect(connection, request, sharedColumnNames, batchSize);
        if (!result.isConsistent()) {
            throw new IllegalStateException("Data consistency verification failed: " + result);
        }
        LOGGER.info("Data consistency verification passed for {} -> {}: liveRows={}, shadowRows={}",
                request.tableName(), request.shadowTableName(),
                result.liveRowCount(), result.shadowRowCount());
        return result;
    }

    public VerificationResult inspect(Connection connection, MigrationRequest request,
                                       List<String> sharedColumnNames, int batchSize)
            throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        MigrationRequest validatedRequest = validateRequest(request);
        List<String> columns = validateColumns(sharedColumnNames);
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be a positive integer");
        }

        long liveRowCount = countRows(connection, validatedRequest.tableName());
        long shadowRowCount = countRows(connection, validatedRequest.shadowTableName());
        List<Long> mismatchedRowIds = findMismatchedRows(
                connection, validatedRequest, columns, batchSize);
        boolean isConsistent = liveRowCount == shadowRowCount && mismatchedRowIds.isEmpty();
        return new VerificationResult(isConsistent, liveRowCount, shadowRowCount, mismatchedRowIds);
    }

    private static long countRows(Connection connection, String tableName) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + tableName;
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet results = statement.executeQuery()) {
            if (!results.next()) {
                throw new SQLException("Row count query returned no result for " + tableName);
            }
            return results.getLong(1);
        }
    }

    private static List<Long> findMismatchedRows(Connection connection, MigrationRequest request,
                                                 List<String> columns, int batchSize)
            throws SQLException {
        String sql = buildPageSql(request, columns);
        List<Long> mismatchedRowIds = new ArrayList<>();
        long lastVerifiedId = INITIAL_LAST_VERIFIED_ID;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            while (true) {
                statement.setLong(1, lastVerifiedId);
                statement.setLong(2, lastVerifiedId);
                statement.setInt(3, batchSize);
                int rowsInPage = 0;
                try (ResultSet results = statement.executeQuery()) {
                    while (results.next()) {
                        long rowId = results.getLong("id");
                        String liveChecksum = results.getString("live_checksum");
                        String shadowChecksum = results.getString("shadow_checksum");
                        if (!Objects.equals(liveChecksum, shadowChecksum)) {
                            mismatchedRowIds.add(rowId);
                        }
                        lastVerifiedId = rowId;
                        rowsInPage++;
                    }
                }
                if (rowsInPage == 0) {
                    return mismatchedRowIds;
                }
            }
        }
    }

    private static String buildPageSql(MigrationRequest request, List<String> columns) {
        String id = quoteIdentifier("id");
        String liveChecksum = checksumExpression("live_row", columns);
        List<String> shadowColumns = columns.stream()
                .map(column -> request.columnMappings().getOrDefault(column, column))
                .toList();
        String shadowChecksum = checksumExpression("shadow_row", shadowColumns);
        return "SELECT page_ids." + id + ", "
                + "CASE WHEN live_row." + id + " IS NOT NULL THEN " + liveChecksum
                + " END AS live_checksum, "
                + "CASE WHEN shadow_row." + id + " IS NOT NULL THEN " + shadowChecksum
                + " END AS shadow_checksum "
                + "FROM (SELECT " + id + " FROM " + request.tableName()
                + " WHERE " + id + " > ? UNION SELECT " + id + " FROM "
                + request.shadowTableName() + " WHERE " + id + " > ? "
                + "ORDER BY " + id + " ASC LIMIT ?) AS page_ids "
                + "LEFT JOIN " + request.tableName() + " AS live_row ON live_row."
                + id + " = page_ids." + id + " "
                + "LEFT JOIN " + request.shadowTableName() + " AS shadow_row ON shadow_row."
                + id + " = page_ids." + id + " "
                + "ORDER BY page_ids." + id + " ASC";
    }

    private static String checksumExpression(String tableAlias, List<String> columns) {
        String qualifiedColumns = columns.stream()
                .map(column -> tableAlias + "." + quoteIdentifier(column))
                .reduce((left, right) -> left + ", " + right)
                .orElseThrow();
        return "md5(CAST(ROW(" + qualifiedColumns + ") AS text))";
    }

    private static List<String> validateColumns(List<String> sharedColumnNames) {
        Objects.requireNonNull(sharedColumnNames, "sharedColumnNames must not be null");
        Set<String> seen = new HashSet<>();
        List<String> columns = new ArrayList<>();
        for (String column : sharedColumnNames) {
            if (column == null || column.isBlank()) {
                throw new IllegalArgumentException("Shared column names must not be null or blank");
            }
            if (!seen.add(column)) {
                throw new IllegalArgumentException("Duplicate shared column: " + column);
            }
            columns.add(column);
        }
        if (!seen.contains("id")) {
            throw new IllegalArgumentException("The shared columns must include the primary key id");
        }
        return List.copyOf(columns);
    }

    private static MigrationRequest validateRequest(MigrationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        MigrationRequest parsedRequest = DdlParser.parse(request.rawSql());
        if (!parsedRequest.equals(request)) {
            throw new IllegalArgumentException("MigrationRequest fields must match its parsed SQL");
        }
        return parsedRequest;
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    public record VerificationResult(boolean isConsistent, long liveRowCount, long shadowRowCount,
                                     List<Long> mismatchedRowIds) {
        public VerificationResult {
            mismatchedRowIds = List.copyOf(
                    Objects.requireNonNull(mismatchedRowIds, "mismatchedRowIds must not be null"));
        }
    }
}
