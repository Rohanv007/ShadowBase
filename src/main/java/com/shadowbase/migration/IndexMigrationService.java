package com.shadowbase.migration;

import com.shadowbase.model.MigrationRequest;
import com.shadowbase.parser.DdlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class IndexMigrationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(IndexMigrationService.class);
    private static final String INDEX_QUERY = """
            SELECT index_relation.relname AS index_name,
                   index_info.indisvalid AS is_valid,
                   pg_get_indexdef(index_info.indexrelid) AS index_definition
            FROM pg_index AS index_info
            JOIN pg_class AS table_relation
              ON table_relation.oid = index_info.indrelid
            JOIN pg_class AS index_relation
              ON index_relation.oid = index_info.indexrelid
            WHERE table_relation.oid = to_regclass(?)
            ORDER BY index_relation.relname
            """;

    public List<IndexInfo> inspectIndexes(Connection connection, MigrationRequest request)
            throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        MigrationRequest validatedRequest = validateRequest(request);
        List<IndexInfo> indexes = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(INDEX_QUERY)) {
            statement.setString(1, validatedRequest.shadowTableName());
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    indexes.add(new IndexInfo(
                            results.getString("index_name"),
                            results.getBoolean("is_valid"),
                            results.getString("index_definition")));
                }
            }
        }
        return List.copyOf(indexes);
    }

    public void createIndexConcurrentlyIfNotExists(Connection connection, MigrationRequest request,
                                                   String indexName, List<String> columnNames)
            throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        MigrationRequest validatedRequest = validateRequest(request);
        if (!connection.getAutoCommit()) {
            throw new IllegalStateException(
                    "CREATE INDEX CONCURRENTLY must run outside a transaction");
        }
        if (indexName == null || indexName.isBlank()) {
            throw new IllegalArgumentException("indexName must not be null or blank");
        }
        Objects.requireNonNull(columnNames, "columnNames must not be null");
        if (columnNames.isEmpty()) {
            throw new IllegalArgumentException("At least one index column is required");
        }
        List<String> columns = columnNames.stream().map(column -> {
            if (column == null || column.isBlank()) {
                throw new IllegalArgumentException("Index column names must not be null or blank");
            }
            return quoteIdentifier(column);
        }).toList();
        if (columns.stream().distinct().count() != columns.size()) {
            throw new IllegalArgumentException("Index columns must not contain duplicates");
        }

        String sql = "CREATE INDEX CONCURRENTLY IF NOT EXISTS " + quoteIdentifier(indexName)
                + " ON " + validatedRequest.shadowTableName()
                + " (" + String.join(", ", columns) + ")";
        LOGGER.info("Creating shadow table index concurrently: {}", sql);
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    public void validateIndexes(Connection connection, MigrationRequest request) throws SQLException {
        List<IndexInfo> invalidIndexes = inspectIndexes(connection, request).stream()
                .filter(index -> !index.valid()).toList();
        if (!invalidIndexes.isEmpty()) {
            String indexNames = invalidIndexes.stream()
                    .map(index -> index.name() + " (" + index.definition() + ")")
                    .reduce((first, second) -> first + ", " + second)
                    .orElseThrow();
            throw new IllegalStateException(
                    "Cannot cut over while shadow table indexes are invalid: " + indexNames);
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

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    public record IndexInfo(String name, boolean valid, String definition) {
        public IndexInfo {
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(definition, "definition must not be null");
        }
    }
}
