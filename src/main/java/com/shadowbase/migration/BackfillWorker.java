package com.shadowbase.migration;

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

public final class BackfillWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(BackfillWorker.class);
    private static final long BATCH_DELAY_MILLIS = 50;
    private static final long INITIAL_LAST_PROCESSED_ID = Long.MIN_VALUE;

    public BackfillProgress backfill(Connection connection, MigrationRequest request,
                                     List<String> sharedColumnNames, int batchSize)
            throws SQLException, InterruptedException {
        Objects.requireNonNull(connection, "connection must not be null");
        MigrationRequest validatedRequest = validateRequest(request);
        List<String> columns = validateColumns(sharedColumnNames);
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be a positive integer");
        }

        String columnList = columns.stream().map(BackfillWorker::quoteIdentifier)
                .reduce((left, right) -> left + ", " + right).orElseThrow();
        String id = quoteIdentifier("id");
        String pageSql = "SELECT " + id + " FROM " + validatedRequest.tableName()
                + " WHERE " + id + " > ? ORDER BY " + id + " ASC LIMIT ?";
        String insertSql = "INSERT INTO " + validatedRequest.shadowTableName()
                + " (" + columnList + ") SELECT " + columnList + " FROM "
                + validatedRequest.tableName() + " WHERE " + id + " > ? ORDER BY "
                + id + " ASC LIMIT ? ON CONFLICT (" + id + ") DO NOTHING";

        Long lastProcessedId = null;
        long totalRowsCopied = 0;
        long batchCount = 0;

        while (true) {
            if (batchCount > 0) {
                try {
                    Thread.sleep(BATCH_DELAY_MILLIS);
                } catch (InterruptedException interruption) {
                    Thread.currentThread().interrupt();
                    throw interruption;
                }
            }

            long afterId = lastProcessedId == null
                    ? INITIAL_LAST_PROCESSED_ID : lastProcessedId;
            int batchRows = 0;
            long batchLastId = afterId;
            try (PreparedStatement pageStatement = connection.prepareStatement(pageSql)) {
                pageStatement.setLong(1, afterId);
                pageStatement.setInt(2, batchSize);
                try (ResultSet results = pageStatement.executeQuery()) {
                    while (results.next()) {
                        batchLastId = results.getLong(1);
                        batchRows++;
                    }
                }
            }

            if (batchRows == 0) {
                LOGGER.info("Backfill complete for {} -> {}: lastProcessedId={}, "
                                + "totalRowsCopied={}, batches={}",
                        validatedRequest.tableName(), validatedRequest.shadowTableName(),
                        lastProcessedId, totalRowsCopied, batchCount);
                return new BackfillProgress(lastProcessedId, totalRowsCopied, batchCount);
            }

            int copiedRows;
            try (PreparedStatement insertStatement = connection.prepareStatement(insertSql)) {
                insertStatement.setLong(1, afterId);
                insertStatement.setInt(2, batchSize);
                copiedRows = insertStatement.executeUpdate();
            }

            lastProcessedId = batchLastId;
            totalRowsCopied += copiedRows;
            batchCount++;
            LOGGER.info("Backfill batch {} for {} -> {}: processed={}, copied={}, "
                            + "totalRowsCopied={}, lastProcessedId={}",
                    batchCount, validatedRequest.tableName(), validatedRequest.shadowTableName(),
                    batchRows, copiedRows, totalRowsCopied, lastProcessedId);
        }
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

    public record BackfillProgress(Long lastProcessedId, long totalRowsCopied, long batchCount) {
    }
}
