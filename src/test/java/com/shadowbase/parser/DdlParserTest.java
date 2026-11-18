package com.shadowbase.parser;

import com.shadowbase.model.MigrationRequest;

public final class DdlParserTest {
    private DdlParserTest() {
    }

    public static void main(String[] args) {
        parsesAddColumnStatement();
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
        assertIllegalArgument(() -> DdlParser.parse("ALTER TABLE users DROP COLUMN phone"));
    }

    private static void assertIllegalArgument(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("Expected IllegalArgumentException");
    }

    private static void assertEquals(String expected, String actual, String description) {
        if (!expected.equals(actual)) {
            throw new AssertionError(description + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
