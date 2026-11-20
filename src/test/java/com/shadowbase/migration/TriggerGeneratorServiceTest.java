package com.shadowbase.migration;

import com.shadowbase.model.MigrationRequest;
import com.shadowbase.parser.DdlParser;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class TriggerGeneratorServiceTest {
    private TriggerGeneratorServiceTest() {
    }

    public static void main(String[] args) throws SQLException {
        createsTriggerFromLiveColumns();
        rejectsSharedColumnsMissingFromLiveTable();
        dropsTriggerAndFunction();
        System.out.println("TriggerGeneratorServiceTest passed");
    }

    private static void createsTriggerFromLiveColumns() throws SQLException {
        List<String> statements = new ArrayList<>();
        List<String> metadataQueries = new ArrayList<>();
        Connection connection = connection(statements, metadataQueries,
                List.of("id", "username", "created_at"));
        MigrationRequest request = DdlParser.parse(
                "ALTER TABLE public.users ADD COLUMN phone VARCHAR(20)");

        new TriggerGeneratorService().createDualWriteTriggers(connection, request,
                List.of("created_at", "id", "username"));

        assertEquals(1, metadataQueries.size(), "metadata query count");
        assertContains(metadataQueries.get(0), "information_schema.columns", "metadata query");
        assertEquals(3, statements.size(), "trigger DDL count");
        assertContains(statements.get(0), "DROP TRIGGER IF EXISTS", "trigger replacement");
        assertContains(statements.get(1), "CREATE OR REPLACE FUNCTION \"public\".\"_shadow_sync_fn_users\"()",
                "function name");
        assertContains(statements.get(1), "INSERT INTO public._shadow_users",
                "shadow table insert");
        assertContains(statements.get(1), "NEW.\"id\", NEW.\"username\", NEW.\"created_at\"",
                "metadata-ordered values");
        assertContains(statements.get(1), "ELSIF TG_OP = 'UPDATE'",
                "update branch");
        assertContains(statements.get(1),
                "DELETE FROM public._shadow_users WHERE \"id\" = OLD.\"id\"",
                "delete branch");
        assertContains(statements.get(2),
                "AFTER INSERT OR UPDATE OR DELETE ON public.users FOR EACH ROW",
                "trigger events");
    }

    private static void rejectsSharedColumnsMissingFromLiveTable() {
        Connection connection = connection(new ArrayList<>(), new ArrayList<>(), List.of("id", "username"));
        MigrationRequest request = DdlParser.parse(
                "ALTER TABLE users ADD COLUMN phone VARCHAR(20)");
        try {
            new TriggerGeneratorService().createDualWriteTriggers(connection, request,
                    List.of("id", "phone"));
        } catch (IllegalArgumentException expected) {
            return;
        } catch (SQLException unexpected) {
            throw new AssertionError("Unexpected SQLException", unexpected);
        }
        throw new AssertionError("Expected missing shared column to be rejected");
    }

    private static void dropsTriggerAndFunction() throws SQLException {
        List<String> statements = new ArrayList<>();
        MigrationRequest request = DdlParser.parse(
                "ALTER TABLE public.users ADD COLUMN phone VARCHAR(20)");
        new TriggerGeneratorService().dropDualWriteTriggers(
                connection(statements, new ArrayList<>(), List.of()), request);

        assertEquals(2, statements.size(), "cleanup DDL count");
        assertContains(statements.get(0), "DROP TRIGGER IF EXISTS \"_shadow_sync_trg_users\"",
                "trigger cleanup");
        assertContains(statements.get(1),
                "DROP FUNCTION IF EXISTS \"public\".\"_shadow_sync_fn_users\"()",
                "function cleanup");
    }

    private static Connection connection(List<String> statements, List<String> metadataQueries,
                                         List<String> liveColumns) {
        InvocationHandler resultSetHandler = new InvocationHandler() {
            private int index = -1;

            @Override
            public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                return switch (method.getName()) {
                    case "next" -> ++index < liveColumns.size();
                    case "getString" -> "table_schema".equals(args[0])
                            ? "public" : liveColumns.get(index);
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
        };
        ResultSet results = (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, resultSetHandler);

        InvocationHandler preparedStatementHandler = (proxy, method, args) -> {
            if ("executeQuery".equals(method.getName())) {
                return results;
            }
            if ("close".equals(method.getName()) || "setString".equals(method.getName())) {
                return null;
            }
            throw new UnsupportedOperationException(method.getName());
        };
        PreparedStatement preparedStatement = (PreparedStatement) Proxy.newProxyInstance(
                PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, preparedStatementHandler);

        InvocationHandler statementHandler = (proxy, method, args) -> {
            if ("execute".equals(method.getName())) {
                statements.add((String) args[0]);
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
            if ("prepareStatement".equals(method.getName())) {
                metadataQueries.add((String) args[0]);
                return preparedStatement;
            }
            if ("createStatement".equals(method.getName())) {
                return statement;
            }
            throw new UnsupportedOperationException(method.getName());
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, connectionHandler);
    }

    private static void assertContains(String actual, String expected, String description) {
        if (!actual.contains(expected)) {
            throw new AssertionError(description + ": expected <" + expected + "> in <" + actual + ">");
        }
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!expected.equals(actual)) {
            throw new AssertionError(description + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
