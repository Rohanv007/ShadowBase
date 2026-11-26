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

public final class IndexMigrationServiceTest {
    private IndexMigrationServiceTest() {
    }

    public static void main(String[] args) throws SQLException {
        createsIndexesConcurrentlyAndInspectsCatalogIndexes();
        rejectsInvalidIndexesBeforeCutover();
        rejectsConcurrentBuildInsideTransaction();
        System.out.println("IndexMigrationServiceTest passed");
    }

    private static void createsIndexesConcurrentlyAndInspectsCatalogIndexes() throws SQLException {
        List<String> statements = new ArrayList<>();
        List<String> parameters = new ArrayList<>();
        Connection connection = connection(statements, parameters, true,
                List.of(new IndexRow("users_name_idx", true, "CREATE INDEX users_name_idx")));

        IndexMigrationService service = new IndexMigrationService();
        service.createIndexConcurrentlyIfNotExists(connection, request(), "users_name_idx",
                List.of("username", "email"));
        List<IndexMigrationService.IndexInfo> indexes = service.inspectIndexes(connection, request());

        assertContains(statements.get(0),
                "CREATE INDEX CONCURRENTLY IF NOT EXISTS \"users_name_idx\" "
                        + "ON public._shadow_users (\"username\", \"email\")",
                "concurrent index DDL");
        assertEquals(List.of("public._shadow_users"), parameters, "catalog table parameter");
        assertEquals(List.of(new IndexMigrationService.IndexInfo(
                "users_name_idx", true, "CREATE INDEX users_name_idx")), indexes, "index catalog");
        service.validateIndexes(connection, request());
    }

    private static void rejectsInvalidIndexesBeforeCutover() throws SQLException {
        Connection connection = connection(new ArrayList<>(), new ArrayList<>(), true,
                List.of(new IndexRow("users_name_idx", false, "CREATE INDEX users_name_idx")));
        try {
            new IndexMigrationService().validateIndexes(connection, request());
            throw new AssertionError("Expected invalid shadow index to block cutover");
        } catch (IllegalStateException expected) {
            assertContains(expected.getMessage(), "users_name_idx", "invalid index name");
            assertContains(expected.getMessage(), "CREATE INDEX users_name_idx",
                    "invalid index definition");
        }
    }

    private static void rejectsConcurrentBuildInsideTransaction() throws SQLException {
        Connection connection = connection(new ArrayList<>(), new ArrayList<>(), false, List.of());
        try {
            new IndexMigrationService().createIndexConcurrentlyIfNotExists(connection, request(),
                    "users_name_idx", List.of("username"));
            throw new AssertionError("Expected concurrent index creation to reject transactions");
        } catch (IllegalStateException expected) {
            assertContains(expected.getMessage(), "outside a transaction", "transaction restriction");
        }
    }

    private static Connection connection(List<String> statements, List<String> parameters,
                                         boolean autoCommit, List<IndexRow> indexes) {
        InvocationHandler preparedStatementHandler = (proxy, method, args) -> {
            if ("setString".equals(method.getName())) {
                parameters.add((String) args[1]);
                return null;
            }
            if ("executeQuery".equals(method.getName())) {
                return resultSet(indexes);
            }
            if ("close".equals(method.getName())) {
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
        Statement statement = (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(),
                new Class<?>[]{Statement.class}, statementHandler);
        InvocationHandler connectionHandler = (proxy, method, args) -> switch (method.getName()) {
            case "getAutoCommit" -> autoCommit;
            case "prepareStatement" -> preparedStatement;
            case "createStatement" -> statement;
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, connectionHandler);
    }

    private static ResultSet resultSet(List<IndexRow> indexes) {
        InvocationHandler resultSetHandler = new InvocationHandler() {
            private int index = -1;

            @Override
            public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                return switch (method.getName()) {
                    case "next" -> ++index < indexes.size();
                    case "getString" -> switch ((String) args[0]) {
                        case "index_name" -> indexes.get(index).name();
                        case "index_definition" -> indexes.get(index).definition();
                        default -> throw new UnsupportedOperationException((String) args[0]);
                    };
                    case "getBoolean" -> indexes.get(index).valid();
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
        };
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, resultSetHandler);
    }

    private static MigrationRequest request() {
        return DdlParser.parse("ALTER TABLE public.users ADD COLUMN phone VARCHAR(20)");
    }

    private static void assertContains(String actual, String expected, String description) {
        if (!actual.contains(expected)) {
            throw new AssertionError(description + ": expected <" + expected + "> in <" + actual + ">");
        }
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!expected.equals(actual)) {
            throw new AssertionError(description + ": expected <" + expected + "> but was <"
                    + actual + ">");
        }
    }

    private record IndexRow(String name, boolean valid, String definition) {
    }
}
