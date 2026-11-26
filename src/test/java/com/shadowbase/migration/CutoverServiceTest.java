package com.shadowbase.migration;

import com.shadowbase.metrics.MigrationMetrics;
import com.shadowbase.model.MigrationRequest;
import com.shadowbase.parser.DdlParser;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class CutoverServiceTest {
    private CutoverServiceTest() {
    }

    public static void main(String[] args) throws SQLException {
        performsTransactionalCutoverAndMeasuresLockTime();
        rollbackPreservesTheDisplacedTable();
        rollsBackWhenPromotionFails();
        System.out.println("CutoverServiceTest passed");
    }

    private static void performsTransactionalCutoverAndMeasuresLockTime()
            throws SQLException {
        List<String> operations = new ArrayList<>();
        MigrationMetrics metrics = new MigrationMetrics();
        String archivedTable = new CutoverService().cutover(
                connection(operations, null), request(), metrics);

        assertEquals("public._old_users", archivedTable, "archived table name");
        assertBefore(operations, "LOCK TABLE public.users, public._shadow_users",
                "DROP TRIGGER IF EXISTS");
        assertBefore(operations, "DROP FUNCTION IF EXISTS",
                "ALTER TABLE public.users RENAME TO _old_users");
        assertBefore(operations, "ALTER TABLE public.users RENAME TO _old_users",
                "ALTER TABLE public._shadow_users RENAME TO users");
        assertEquals(true, operations.contains("COMMIT"), "cutover commit");
        if (!metrics.formatSummaryReport().contains("Cutover pause: "
                + metrics.getCutoverLockDurationMs() + " ms")
                || metrics.getCurrentPhase().name().equals("FAILED")) {
            throw new AssertionError("Cutover metrics were not recorded");
        }
    }

    private static void rollbackPreservesTheDisplacedTable() throws SQLException {
        List<String> operations = new ArrayList<>();
        String displacedTable = new CutoverService().rollbackCutover(
                connection(operations, null), "public.users", "public._old_users");

        assertEquals("public._failed_users", displacedTable, "displaced table name");
        assertBefore(operations, "LOCK TABLE public.users, public._old_users",
                "ALTER TABLE public.users RENAME TO _failed_users");
        assertBefore(operations, "ALTER TABLE public.users RENAME TO _failed_users",
                "ALTER TABLE public._old_users RENAME TO users");
        assertEquals(true, operations.contains("COMMIT"), "rollback commit");
    }

    private static void rollsBackWhenPromotionFails() throws SQLException {
        List<String> operations = new ArrayList<>();
        try {
            new CutoverService().cutover(connection(operations,
                    "ALTER TABLE public._shadow_users RENAME TO users"), request(),
                    new MigrationMetrics());
            throw new AssertionError("Expected cutover to fail");
        } catch (SQLException actualFailure) {
            if (!"simulated rename failure".equals(actualFailure.getMessage())) {
                throw new AssertionError("Cutover must preserve the SQL failure", actualFailure);
            }
        }
        if (!operations.contains("ROLLBACK") || !operations.contains("AUTOCOMMIT true")) {
            throw new AssertionError("Failed cutover must roll back and restore auto-commit");
        }
    }

    private static MigrationRequest request() {
        return DdlParser.parse("ALTER TABLE public.users ADD COLUMN phone VARCHAR(20)");
    }

    private static Connection connection(List<String> operations, String failingSql) {
        InvocationHandler statementHandler = (proxy, method, args) -> {
            if ("execute".equals(method.getName())) {
                String sql = (String) args[0];
                operations.add(sql);
                if (sql.equals(failingSql)) {
                    throw new SQLException("simulated rename failure");
                }
                return true;
            }
            if ("close".equals(method.getName())) {
                return null;
            }
            throw new UnsupportedOperationException(method.getName());
        };
        InvocationHandler connectionHandler = new InvocationHandler() {
            private boolean autoCommit = true;

            @Override
            public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args)
                    throws Throwable {
                return switch (method.getName()) {
                    case "getAutoCommit" -> autoCommit;
                    case "setAutoCommit" -> {
                        autoCommit = (boolean) args[0];
                        operations.add("AUTOCOMMIT " + autoCommit);
                        yield null;
                    }
                    case "createStatement" -> Proxy.newProxyInstance(
                            Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                            statementHandler);
                    case "commit" -> {
                        operations.add("COMMIT");
                        yield null;
                    }
                    case "rollback" -> {
                        operations.add("ROLLBACK");
                        yield null;
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, connectionHandler);
    }

    private static void assertBefore(List<String> operations, String first, String second) {
        int firstIndex = indexOf(operations, first);
        int secondIndex = indexOf(operations, second);
        if (firstIndex < 0 || secondIndex < 0 || firstIndex >= secondIndex) {
            throw new AssertionError("Expected " + first + " before " + second
                    + " in " + operations);
        }
    }

    private static int indexOf(List<String> operations, String fragment) {
        for (int index = 0; index < operations.size(); index++) {
            if (operations.get(index).contains(fragment)) {
                return index;
            }
        }
        return -1;
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!expected.equals(actual)) {
            throw new AssertionError(description + ": expected <" + expected + "> but was <"
                    + actual + ">");
        }
    }
}
