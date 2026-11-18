package com.shadowbase.model;

import java.util.Objects;

public record MigrationRequest(
        String tableName,
        String shadowTableName,
        String alterClause,
        String rawSql) {

    public MigrationRequest {
        Objects.requireNonNull(tableName, "tableName must not be null");
        Objects.requireNonNull(shadowTableName, "shadowTableName must not be null");
        Objects.requireNonNull(alterClause, "alterClause must not be null");
        Objects.requireNonNull(rawSql, "rawSql must not be null");
    }
}
