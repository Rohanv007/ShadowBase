package com.shadowbase.migration;

import com.shadowbase.model.MigrationRequest;
import com.shadowbase.parser.DdlParser;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class ShadowTableServiceTest {
    private ShadowTableServiceTest() {
    }

    public static void main(String[] args) throws SQLException {
        provisionsShadowTableInOrder();
        cleansUpWhenAlterationFails();
        rejectsRequestThatDoesNotMatchItsSql();
        System.out.println("ShadowTableServiceTest passed");
    }

    private static void provisionsShadowTableInOrder() throws SQLException {
        List<String> statements = new ArrayList<>();
        new ShadowTableService().provision(connection(statements, null),
                DdlParser.parse("ALTER TABLE public.users ADD COLUMN phone VARCHAR(20)"));

        assertEquals(List.of(
                "DROP TABLE IF EXISTS public._shadow_users",
                "CREATE TABLE public._shadow_users (LIKE public.users INCLUDING ALL)",
                "ALTER TABLE public._shadow_users ADD COLUMN phone VARCHAR(20)"),
                statements, "provisioning statements");
    }

    private static void cleansUpWhenAlterationFails() {
        List<String> statements = new ArrayList<>();
        SQLException expected = new SQLException("simulated ALTER failure");
        try {
            new ShadowTableService().provision(connection(statements, expected),
                    DdlParser.parse("ALTER TABLE users ADD COLUMN phone VARCHAR(20)"));
            throw new AssertionError("Expected SQLException");
        } catch (SQLException actual) {
            if (actual != expected) {
                throw new AssertionError("Expected the original SQLException", actual);
            }
        }

        assertEquals(List.of(
                "DROP TABLE IF EXISTS _shadow_users",
                "CREATE TABLE _shadow_users (LIKE users INCLUDING ALL)",
                "ALTER TABLE _shadow_users ADD COLUMN phone VARCHAR(20)",
                "DROP TABLE IF EXISTS _shadow_users"),
                statements, "cleanup statements");
    }

    private static void rejectsRequestThatDoesNotMatchItsSql() {
        MigrationRequest parsed = DdlParser.parse("ALTER TABLE users ADD COLUMN phone VARCHAR(20)");
        MigrationRequest forged = new MigrationRequest(parsed.tableName(), "other_table",
                parsed.alterClause(), parsed.rawSql());
        try {
            new ShadowTableService().provision(connection(new ArrayList<>(), null), forged);
        } catch (IllegalArgumentException expected) {
            return;
        } catch (SQLException unexpected) {
            throw new AssertionError("Unexpected SQLException", unexpected);
        }
        throw new AssertionError("Expected inconsistent request to be rejected");
    }

    private static Connection connection(List<String> statements, SQLException failure) {
        InvocationHandler statementHandler = (proxy, method, args) -> {
            if ("execute".equals(method.getName())) {
                String sql = (String) args[0];
                statements.add(sql);
                if (failure != null && sql.startsWith("ALTER TABLE")) {
                    throw failure;
                }
                return true;
            }
            if ("close".equals(method.getName())) {
                return null;
            }
            throw new UnsupportedOperationException(method.getName());
        };
        Statement statement = (Statement) Proxy.newProxyInstance(
                Statement.class.getClassLoader(), new Class<?>[]{Statement.class}, statementHandler);

        InvocationHandler connectionHandler = (proxy, method, args) -> {
            if ("getAutoCommit".equals(method.getName())) {
                return true;
            }
            if ("createStatement".equals(method.getName())) {
                return statement;
            }
            throw new UnsupportedOperationException(method.getName());
        };
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, connectionHandler);
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!expected.equals(actual)) {
            throw new AssertionError(description + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
