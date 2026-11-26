package com.shadowbase.parser;

import com.shadowbase.model.MigrationRequest;

import java.util.List;
import java.util.Map;

public final class DdlParserTest {
    private DdlParserTest() {
    }

    public static void main(String[] args) {
        parsesAddColumnStatement();
        parsesMultipleColumnActionsAndMappings();
        parsesChainedRenamesAndDroppedRenamedColumns();
        derivesShadowTableNameForQualifiedTable();
        rejectsInvalidAndUnsupportedStatements();
        rejectsDestructiveStatements();
        System.out.println("DdlParserTest passed");
    }

    private static void parsesAddColumnStatement() {
        String sql = "ALTER TABLE users ADD COLUMN phone VARCHAR(20);";
        MigrationRequest request = DdlParser.parse(sql);

        assertEquals("users", request.tableName(), "table name");
        assertEquals("_shadow_users", request.shadowTableName(), "shadow table name");
        assertEquals("ADD COLUMN phone VARCHAR(20)", request.alterClause(), "alter clause");
        assertEquals(sql, request.rawSql(), "raw SQL");
    }

    private static void derivesShadowTableNameForQualifiedTable() {
        MigrationRequest request = DdlParser.parse(
                "ALTER TABLE public.users ADD COLUMN phone VARCHAR(20)");

        assertEquals("public.users", request.tableName(), "qualified table name");
        assertEquals("public._shadow_users", request.shadowTableName(), "qualified shadow table name");
    }

    private static void parsesMultipleColumnActionsAndMappings() {
        MigrationRequest request = DdlParser.parse("ALTER TABLE public.users "
                + "ADD COLUMN score NUMERIC(10, 2) DEFAULT 0, "
                + "DROP COLUMN email, RENAME COLUMN username TO handle, "
                + "ALTER COLUMN id TYPE BIGINT");

        assertEquals(List.of("email"), request.droppedColumns(), "dropped columns");
        assertEquals(Map.of("username", "handle"), request.columnMappings(), "column mappings");
        assertEquals("ADD COLUMN score NUMERIC(10, 2) DEFAULT 0, DROP COLUMN email, "
                        + "RENAME COLUMN username TO handle, ALTER COLUMN id TYPE BIGINT",
                request.alterClause(), "multi-action alter clause");
    }

    private static void parsesChainedRenamesAndDroppedRenamedColumns() {
        MigrationRequest renamed = DdlParser.parse(
                "ALTER TABLE users RENAME COLUMN username TO handle, "
                        + "RENAME COLUMN handle TO display_name");
        assertEquals(Map.of("username", "display_name"), renamed.columnMappings(),
                "chained rename mapping");

        MigrationRequest dropped = DdlParser.parse(
                "ALTER TABLE users RENAME COLUMN username TO handle, DROP COLUMN handle");
        assertEquals(List.of("username"), dropped.droppedColumns(),
                "drop after rename resolves to live column");
        assertEquals(Map.of(), dropped.columnMappings(), "removed rename mapping");
    }

    private static void rejectsInvalidAndUnsupportedStatements() {
        assertIllegalArgument(() -> DdlParser.parse("ALTER TABLE users ADD COLUMN"));
        assertIllegalArgument(() -> DdlParser.parse("ALTER TABLE users ADD COLUMN phone $$$"));
        assertIllegalArgument(() -> DdlParser.parse("UPDATE users SET phone = '123'"));
        assertIllegalArgument(() -> DdlParser.parse("ALTER TABLE users RENAME TO people"));
        assertIllegalArgument(() -> DdlParser.parse("ALTER TABLE users ADD COLUMN phone TEXT; DROP TABLE users"));
    }

    private static void rejectsDestructiveStatements() {
        assertIllegalArgument(() -> DdlParser.parse("DROP TABLE users"));
        assertIllegalArgument(() -> DdlParser.parse("TRUNCATE TABLE users"));
        assertIllegalArgument(() -> DdlParser.parse("ALTER TABLE users DROP INDEX phone_idx"));
    }

    private static void assertIllegalArgument(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected IllegalArgumentException");
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!expected.equals(actual)) {
            throw new AssertionError(description + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
