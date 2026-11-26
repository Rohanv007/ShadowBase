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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class TriggerGeneratorService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TriggerGeneratorService.class);
    private static final String COLUMN_QUERY = """
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = COALESCE(?, current_schema())
              AND table_name = ?
            ORDER BY ordinal_position
            """;

    public List<String> getLiveTableColumns(Connection connection, MigrationRequest request)
            throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        MigrationRequest validatedRequest = validateRequest(request);
        QualifiedTableName table = QualifiedTableName.parse(validatedRequest.tableName());
        List<String> liveColumns = inspectColumns(connection, table).columns();
        List<String> shadowColumns = inspectColumns(connection,
                QualifiedTableName.parse(validatedRequest.shadowTableName())).columns();
        return resolveSharedColumns(liveColumns, shadowColumns, liveColumns, validatedRequest);
    }

    public void createDualWriteTriggers(Connection connection, MigrationRequest request,
                                        List<String> sharedColumnNames) throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        MigrationRequest validatedRequest = validateRequest(request);
        Objects.requireNonNull(sharedColumnNames, "sharedColumnNames must not be null");

        QualifiedTableName table = QualifiedTableName.parse(validatedRequest.tableName());
        List<String> liveColumns = inspectColumns(connection, table).columns();
        List<String> shadowColumns = inspectColumns(connection,
                QualifiedTableName.parse(validatedRequest.shadowTableName())).columns();
        List<String> columns = resolveSharedColumns(
                liveColumns, shadowColumns, sharedColumnNames, validatedRequest);
        if (!columns.contains("id")) {
            throw new IllegalArgumentException("The shared columns must include the primary key id");
        }

        String shadowTable = validatedRequest.shadowTableName();
        String functionName = table.qualify("_shadow_sync_fn_" + table.table());
        String triggerName = "_shadow_sync_trg_" + table.table();
        String insertSql = buildUpsertSql(shadowTable, columns, validatedRequest.columnMappings());
        String functionSql = buildFunctionSql(functionName, insertSql, shadowTable, "id");
        String triggerSql = "CREATE TRIGGER " + quoteIdentifier(triggerName)
                + " AFTER INSERT OR UPDATE OR DELETE ON " + validatedRequest.tableName()
                + " FOR EACH ROW EXECUTE FUNCTION " + functionName + "()";

        try (Statement statement = connection.createStatement()) {
            execute(statement, "DROP TRIGGER IF EXISTS " + quoteIdentifier(triggerName)
                    + " ON " + validatedRequest.tableName());
            execute(statement, functionSql);
            execute(statement, triggerSql);
        }
        LOGGER.info("Dual-write trigger installed on {}", validatedRequest.tableName());
    }

    public void dropDualWriteTriggers(Connection connection, MigrationRequest request) throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        MigrationRequest validatedRequest = validateRequest(request);
        QualifiedTableName table = QualifiedTableName.parse(validatedRequest.tableName());
        String triggerName = "_shadow_sync_trg_" + table.table();
        String functionName = table.qualify("_shadow_sync_fn_" + table.table());

        try (Statement statement = connection.createStatement()) {
            execute(statement, "DROP TRIGGER IF EXISTS " + quoteIdentifier(triggerName)
                    + " ON " + validatedRequest.tableName());
            execute(statement, "DROP FUNCTION IF EXISTS " + functionName + "()");
        }
        LOGGER.info("Dual-write trigger and function removed from {}", validatedRequest.tableName());
    }

    private static List<String> resolveSharedColumns(List<String> liveColumns,
                                                     List<String> shadowColumns,
                                                     List<String> sharedColumnNames,
                                                     MigrationRequest request) {
        Set<String> requested = new HashSet<>();
        for (String column : sharedColumnNames) {
            if (column == null || column.isBlank()) {
                throw new IllegalArgumentException("Shared column names must not be null or blank");
            }
            if (!requested.add(column)) {
                throw new IllegalArgumentException("Duplicate shared column: " + column);
            }
            if (!liveColumns.contains(column)) {
                throw new IllegalArgumentException(
                        "Shared column does not exist on the live table: " + column);
            }
        }

        List<String> orderedColumns = new ArrayList<>();
        for (String liveColumn : liveColumns) {
            String shadowColumn = request.columnMappings().getOrDefault(liveColumn, liveColumn);
            if (requested.contains(liveColumn)
                    && !request.droppedColumns().contains(liveColumn)
                    && shadowColumns.contains(shadowColumn)) {
                orderedColumns.add(liveColumn);
            }
        }
        return List.copyOf(orderedColumns);
    }

    private static ColumnMetadata inspectColumns(Connection connection, QualifiedTableName table)
            throws SQLException {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(COLUMN_QUERY)) {
            statement.setString(1, table.schema());
            statement.setString(2, table.table());
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    columns.add(results.getString("column_name"));
                }
            }
        }
        if (columns.isEmpty()) {
            throw new SQLException("No visible columns found for table " + table.sqlName());
        }
        return new ColumnMetadata(List.copyOf(columns));
    }

    private static String buildUpsertSql(String shadowTable, List<String> columns,
                                         Map<String, String> columnMappings) {
        List<String> shadowColumns = columns.stream()
                .map(column -> columnMappings.getOrDefault(column, column)).toList();
        String columnList = shadowColumns.stream().map(TriggerGeneratorService::quoteIdentifier)
                .reduce((left, right) -> left + ", " + right).orElseThrow();
        String newValues = columns.stream().map(column -> "NEW." + quoteIdentifier(column))
                .reduce((left, right) -> left + ", " + right).orElseThrow();
        List<String> updates = new ArrayList<>();
        for (int index = 0; index < columns.size(); index++) {
            if (!"id".equals(columns.get(index))) {
                String shadowColumn = quoteIdentifier(shadowColumns.get(index));
                updates.add(shadowColumn + " = EXCLUDED." + shadowColumn);
            }
        }
        String conflictAction = updates.isEmpty()
                ? "DO NOTHING"
                : "DO UPDATE SET " + String.join(", ", updates);

        return "INSERT INTO " + shadowTable + " (" + columnList + ") VALUES (" + newValues
                + ") ON CONFLICT (\"id\") " + conflictAction + ";";
    }

    private static String buildFunctionSql(String functionName, String insertSql,
                                           String shadowTable, String primaryKey) {
        String quotedPrimaryKey = quoteIdentifier(primaryKey);
        return """
                CREATE OR REPLACE FUNCTION %s()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    IF TG_OP = 'INSERT' THEN
                        %s
                        RETURN NEW;
                    ELSIF TG_OP = 'UPDATE' THEN
                        %s
                        RETURN NEW;
                    ELSIF TG_OP = 'DELETE' THEN
                        DELETE FROM %s WHERE %s = OLD.%s;
                        RETURN OLD;
                    END IF;
                    RETURN NULL;
                END;
                $$;
                """.formatted(functionName, insertSql, insertSql, shadowTable,
                quotedPrimaryKey, quotedPrimaryKey);
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

    private static void execute(Statement statement, String sql) throws SQLException {
        LOGGER.info("Executing dual-write trigger DDL: {}", sql);
        statement.execute(sql);
    }

    private record ColumnMetadata(List<String> columns) {
    }

    private record QualifiedTableName(String schema, String table) {
        private static QualifiedTableName parse(String name) {
            boolean inQuotedIdentifier = false;
            int separator = -1;
            for (int index = 0; index < name.length(); index++) {
                char current = name.charAt(index);
                if (current == '"') {
                    if (inQuotedIdentifier && index + 1 < name.length()
                            && name.charAt(index + 1) == '"') {
                        index++;
                    } else {
                        inQuotedIdentifier = !inQuotedIdentifier;
                    }
                } else if (current == '.' && !inQuotedIdentifier) {
                    separator = index;
                }
            }
            if (separator < 0) {
                return new QualifiedTableName(null, unquote(name));
            }
            return new QualifiedTableName(unquote(name.substring(0, separator)),
                    unquote(name.substring(separator + 1)));
        }

        private String sqlName() {
            return schema == null ? quoteIdentifier(table)
                    : quoteIdentifier(schema) + "." + quoteIdentifier(table);
        }

        private String qualify(String identifier) {
            return schema == null ? quoteIdentifier(identifier)
                    : quoteIdentifier(schema) + "." + quoteIdentifier(identifier);
        }

        private static String unquote(String identifier) {
            if (identifier.startsWith("\"") && identifier.endsWith("\"")) {
                return identifier.substring(1, identifier.length() - 1).replace("\"\"", "\"");
            }
            return identifier;
        }
    }
}
