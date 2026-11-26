package com.shadowbase.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record MigrationRequest(
        String tableName,
        String shadowTableName,
        String alterClause,
        String rawSql,
        List<String> droppedColumns,
        Map<String, String> columnMappings) {

    public MigrationRequest {
        Objects.requireNonNull(tableName, "tableName must not be null");
        Objects.requireNonNull(shadowTableName, "shadowTableName must not be null");
        Objects.requireNonNull(alterClause, "alterClause must not be null");
        Objects.requireNonNull(rawSql, "rawSql must not be null");
        droppedColumns = List.copyOf(
                Objects.requireNonNull(droppedColumns, "droppedColumns must not be null"));
        columnMappings = Map.copyOf(
                Objects.requireNonNull(columnMappings, "columnMappings must not be null"));
    }

    public MigrationRequest(String tableName, String shadowTableName, String alterClause,
                            String rawSql) {
        this(tableName, shadowTableName, alterClause, rawSql, List.of(), Map.of());
    }
}
