package com.shadowbase;

import com.shadowbase.migration.ShadowTableService;
import com.shadowbase.model.MigrationRequest;
import com.shadowbase.parser.DdlParser;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class MigrationPipelineSimulationTest {
    private MigrationPipelineSimulationTest() {
    }

    public static void main(String[] args) throws SQLException {
        validatesParsedMigrationAndShadowName();
        rejectsRequestThatDoesNotMatchItsSql();
        rollsBackFailedShadowProvisioningBeforeCleanup();
        System.out.println("MigrationPipelineSimulationTest passed");
    }

    private static void validatesParsedMigrationAndShadowName() {
        String sql = "ALTER TABLE public.users ADD COLUMN phone VARCHAR(20)";
        MigrationRequest request = DdlParser.parse(sql);

        assertEquals("public.users", request.tableName(), "live table");
        assertEquals("public._shadow_users", request.shadowTableName(), "shadow table");
        assertEquals("ADD COLUMN phone VARCHAR(20)", request.alterClause(), "alter clause");
        assertEquals(sql, request.rawSql(), "source SQL");

        MigrationRequest reconstructed = DdlParser.parse(request.rawSql());
        assertEquals(request, reconstructed, "request round-trip");
    }

    private static void rejectsRequestThatDoesNotMatchItsSql() {
        MigrationRequest parsed = DdlParser.parse(
                "ALTER TABLE users ADD COLUMN phone VARCHAR(20)");
        MigrationRequest forged = new MigrationRequest(parsed.tableName(), "wrong_shadow",
                parsed.alterClause(), parsed.rawSql());
        List<String> operations = new ArrayList<>();

        try {
            new ShadowTableService().provision(connection(operations, null,
                    new SQLException("unexpected database operation")), forged);
            throw new AssertionError("Expected mismatched request to be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals(List.of(), operations, "operations for invalid request");
        } catch (SQLException unexpected) {
            throw new AssertionError("Invalid request reached the database", unexpected);
        }
    }

    private static void rollsBackFailedShadowProvisioningBeforeCleanup() throws SQLException {
        List<String> operations = new ArrayList<>();
        Savepoint savepoint = (Savepoint) Proxy.newProxyInstance(
                Savepoint.class.getClassLoader(), new Class<?>[]{Savepoint.class},
                (proxy, method, args) -> null);
        SQLException expectedFailure = new SQLException("simulated shadow ALTER failure");

        try {
            new ShadowTableService().provision(connection(operations, savepoint, expectedFailure),
                    DdlParser.parse("ALTER TABLE users ADD COLUMN phone VARCHAR(20)"));
            throw new AssertionError("Expected shadow provisioning to fail");
        } catch (SQLException actualFailure) {
            if (actualFailure != expectedFailure) {
                throw new AssertionError("The original DDL failure must be preserved", actualFailure);
            }
        }

        assertEquals(List.of(
                "CREATE SAVEPOINT",
                "DROP TABLE IF EXISTS _shadow_users",
                "CREATE TABLE _shadow_users (LIKE users INCLUDING ALL)",
                "ALTER TABLE _shadow_users ADD COLUMN phone VARCHAR(20)",
                "ROLLBACK TO SAVEPOINT",
                "DROP TABLE IF EXISTS _shadow_users"), operations, "rollback sequence");
        if (operations.stream().anyMatch(sql -> sql.equals("DROP TABLE IF EXISTS users"))) {
            throw new AssertionError("Rollback cleanup must never drop the live table");
        }
    }

    private static Connection connection(List<String> operations, Savepoint savepoint,
                                         SQLException alterFailure) {
        InvocationHandler statementHandler = (proxy, method, args) -> {
            if ("execute".equals(method.getName())) {
                String sql = (String) args[0];
                operations.add(sql);
                if (sql.startsWith("ALTER TABLE")) {
                    throw alterFailure;
                }
                return true;
            }
            if ("close".equals(method.getName())) {
                return null;
            }
            throw new UnsupportedOperationException(method.getName());
        };

        InvocationHandler connectionHandler = (proxy, method, args) -> {
            if ("getAutoCommit".equals(method.getName())) {
                return false;
            }
            if ("setSavepoint".equals(method.getName())) {
                operations.add("CREATE SAVEPOINT");
                return savepoint;
            }
            if ("createStatement".equals(method.getName())) {
                return Proxy.newProxyInstance(Statement.class.getClassLoader(),
                        new Class<?>[]{Statement.class}, statementHandler);
            }
            if ("rollback".equals(method.getName())) {
                operations.add("ROLLBACK TO SAVEPOINT");
                return null;
            }
            throw new UnsupportedOperationException(method.getName());
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, connectionHandler);
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!expected.equals(actual)) {
            throw new AssertionError(description + ": expected <" + expected + "> but was <"
                    + actual + ">");
        }
    }
}
