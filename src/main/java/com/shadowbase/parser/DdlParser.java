package com.shadowbase.parser;

import com.shadowbase.model.MigrationRequest;

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
                    + "(?:\\s*\\.\\s*" + IDENTIFIER + ")?)\\s+(?<action>.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ADD_COLUMN = Pattern.compile(
            "^ADD\\s+(?:COLUMN\\s+)?(?:IF\\s+NOT\\s+EXISTS\\s+)?"
                    + IDENTIFIER + "\\s+" + TYPE_DECLARATION + "$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DESTRUCTIVE_OPERATION = Pattern.compile(
            "\\b(?:DROP|TRUNCATE)\\b", Pattern.CASE_INSENSITIVE);

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
        if (DESTRUCTIVE_OPERATION.matcher(statement).find()) {
            throw new IllegalArgumentException("Destructive DROP and TRUNCATE operations are not supported");
        }

        Matcher alterMatcher = ALTER_TABLE.matcher(statement);
        if (!alterMatcher.matches()) {
            throw new IllegalArgumentException("Expected ALTER TABLE <table_name> <action>");
        }

        String tableName = alterMatcher.group("table").replaceAll("\\s*\\.\\s*", ".");
        String alterClause = alterMatcher.group("action").trim();
        Matcher addMatcher = ADD_COLUMN.matcher(alterClause);
        if (!addMatcher.matches()) {
            throw new IllegalArgumentException(
                    "Only non-destructive ADD COLUMN actions with a supported type declaration are supported");
        }

        return new MigrationRequest(tableName, shadowTableName(tableName), alterClause, rawSql);
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
