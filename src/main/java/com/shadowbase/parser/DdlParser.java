package com.shadowbase.parser;

import com.shadowbase.model.MigrationRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DdlParser {
    private static final String IDENTIFIER = "(?:[A-Za-z_][A-Za-z0-9_$]*|\"(?:[^\"]|\"\")+\")";
    private static final String TYPE_DECLARATION = IDENTIFIER
            + "(?:\\s*\\.\\s*" + IDENTIFIER + ")?"
            + "(?:\\s+(?:WITH\\s+TIME\\s+ZONE|WITHOUT\\s+TIME\\s+ZONE|PRECISION|VARYING))?"
            + "(?:\\s*\\(\\s*\\d+(?:\\s*,\\s*\\d+)?\\s*\\))?(?:\\[\\])*";
    private static final Pattern ALTER_TABLE = Pattern.compile(
            "^ALTER\\s+TABLE\\s+(?:ONLY\\s+)?(?<table>" + IDENTIFIER
                    + "(?:\\s*\\.\\s*" + IDENTIFIER + ")?)\\s+(?<actions>.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ADD_COLUMN = Pattern.compile(
            "^ADD\\s+(?:COLUMN\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?"
                    + IDENTIFIER + "\\s+" + TYPE_DECLARATION
                    + "(?:\\s+(?:NOT\\s+NULL|NULL|DEFAULT\\s+.+|UNIQUE|PRIMARY\\s+KEY"
                    + "|CHECK\\s*\\(.+\\)|REFERENCES\\s+.+))*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DROP_COLUMN = Pattern.compile(
            "^DROP\\s+COLUMN\\s+(?:IF\\s+EXISTS\\s+)?(?<column>" + IDENTIFIER
                    + ")(?:\\s+(?:CASCADE|RESTRICT))?$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern RENAME_COLUMN = Pattern.compile(
            "^RENAME\\s+COLUMN\\s+(?<from>" + IDENTIFIER + ")\\s+TO\\s+(?<to>"
                    + IDENTIFIER + ")$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ALTER_COLUMN_TYPE = Pattern.compile(
            "^ALTER\\s+(?:COLUMN\\s+)?(?<column>" + IDENTIFIER
                    + ")\\s+(?:TYPE|SET\\s+DATA\\s+TYPE)\\s+" + TYPE_DECLARATION
                    + "(?:\\s+USING\\s+.+)?$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private DdlParser() {
    }

    public static MigrationRequest parse(String rawSql) {
        if (rawSql == null || rawSql.isBlank()) {
            throw new IllegalArgumentException("SQL must not be null or blank");
        }

        String statement = rawSql.trim();
        if (statement.endsWith(";")) {
            statement = statement.substring(0, statement.length() - 1).trim();
        }
        if (statement.indexOf(';') >= 0) {
            throw new IllegalArgumentException("Only one SQL statement is supported");
        }
        if (statement.contains("--") || statement.contains("/*") || statement.contains("*/")) {
            throw new IllegalArgumentException("SQL comments are not supported");
        }

        Matcher alterMatcher = ALTER_TABLE.matcher(statement);
        if (!alterMatcher.matches()) {
            throw new IllegalArgumentException("Expected ALTER TABLE <table_name> <action>");
        }

        String tableName = alterMatcher.group("table").replaceAll("\\s*\\.\\s*", ".");
        String alterClause = alterMatcher.group("actions").trim();
        List<String> actions = splitActions(alterClause);
        Set<String> droppedColumns = new LinkedHashSet<>();
        Map<String, String> columnMappings = new LinkedHashMap<>();

        for (String action : actions) {
            if (ADD_COLUMN.matcher(action).matches()
                    || ALTER_COLUMN_TYPE.matcher(action).matches()) {
                continue;
            }

            Matcher dropMatcher = DROP_COLUMN.matcher(action);
            if (dropMatcher.matches()) {
                recordDroppedColumn(dropMatcher.group("column"), droppedColumns, columnMappings);
                continue;
            }

            Matcher renameMatcher = RENAME_COLUMN.matcher(action);
            if (renameMatcher.matches()) {
                recordColumnMapping(renameMatcher.group("from"), renameMatcher.group("to"),
                        columnMappings);
                continue;
            }

            throw new IllegalArgumentException(
                    "Only ADD COLUMN, DROP COLUMN, RENAME COLUMN, and ALTER COLUMN TYPE actions "
                            + "are supported");
        }

        return new MigrationRequest(tableName, shadowTableName(tableName), alterClause, rawSql,
                List.copyOf(droppedColumns), Map.copyOf(columnMappings));
    }

    private static List<String> splitActions(String alterClause) {
        List<String> actions = new ArrayList<>();
        int actionStart = 0;
        int parenthesesDepth = 0;
        boolean inQuotedIdentifier = false;
        boolean inStringLiteral = false;

        for (int index = 0; index < alterClause.length(); index++) {
            char current = alterClause.charAt(index);
            if (inQuotedIdentifier) {
                if (current == '"' && index + 1 < alterClause.length()
                        && alterClause.charAt(index + 1) == '"') {
                    index++;
                } else if (current == '"') {
                    inQuotedIdentifier = false;
                }
            } else if (inStringLiteral) {
                if (current == '\'' && index + 1 < alterClause.length()
                        && alterClause.charAt(index + 1) == '\'') {
                    index++;
                } else if (current == '\'') {
                    inStringLiteral = false;
                }
            } else if (current == '"') {
                inQuotedIdentifier = true;
            } else if (current == '\'') {
                inStringLiteral = true;
            } else if (current == '(') {
                parenthesesDepth++;
            } else if (current == ')') {
                if (--parenthesesDepth < 0) {
                    throw new IllegalArgumentException("Unbalanced parentheses in ALTER TABLE actions");
                }
            } else if (current == ',' && parenthesesDepth == 0) {
                addAction(actions, alterClause.substring(actionStart, index));
                actionStart = index + 1;
            }
        }

        if (parenthesesDepth != 0 || inQuotedIdentifier || inStringLiteral) {
            throw new IllegalArgumentException("Unterminated quoted value or parentheses in ALTER TABLE actions");
        }
        addAction(actions, alterClause.substring(actionStart));
        return List.copyOf(actions);
    }

    private static void addAction(List<String> actions, String action) {
        String trimmed = action.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("ALTER TABLE actions must not be empty");
        }
        actions.add(trimmed);
    }

    private static void recordDroppedColumn(String column, Set<String> droppedColumns,
                                            Map<String, String> columnMappings) {
        String sourceColumn;
        if (columnMappings.containsKey(column)) {
            columnMappings.remove(column);
            sourceColumn = column;
        } else {
            sourceColumn = null;
            for (Map.Entry<String, String> mapping : columnMappings.entrySet()) {
                if (mapping.getValue().equals(column)) {
                    sourceColumn = mapping.getKey();
                    break;
                }
            }
        }
        if (sourceColumn != null) {
            columnMappings.remove(sourceColumn);
            droppedColumns.add(sourceColumn);
        } else {
            droppedColumns.add(column);
        }
    }

    private static void recordColumnMapping(String from, String to,
                                            Map<String, String> columnMappings) {
        String sourceColumn = columnMappings.containsKey(from) ? from : null;
        if (sourceColumn == null) {
            for (Map.Entry<String, String> mapping : columnMappings.entrySet()) {
                if (mapping.getValue().equals(from)) {
                    sourceColumn = mapping.getKey();
                    break;
                }
            }
        }
        columnMappings.put(sourceColumn == null ? from : sourceColumn, to);
    }

    private static String shadowTableName(String tableName) {
        boolean inQuotedIdentifier = false;
        int lastSeparator = -1;

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
                lastSeparator = index;
            }
        }

        String qualifier = tableName.substring(0, lastSeparator + 1);
        String table = tableName.substring(lastSeparator + 1);
        if (table.startsWith("\"")) {
            return qualifier + "\"_shadow_" + table.substring(1);
        }
        return qualifier + "_shadow_" + table;
    }
}
