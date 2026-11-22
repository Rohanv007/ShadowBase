package com.shadowbase.verification;

import com.shadowbase.model.MigrationRequest;
import com.shadowbase.parser.DdlParser;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class ConsistencyVerifierTest {
    private ConsistencyVerifierTest() {
    }

    public static void main(String[] args) throws SQLException {
        verifiesMatchingRowsUsingKeysetChecksumPages();
        reportsMismatchedRowsAndBlocksVerification();
        System.out.println("ConsistencyVerifierTest passed");
    }

    private static void verifiesMatchingRowsUsingKeysetChecksumPages() throws SQLException {
        List<String> preparedSql = new ArrayList<>();
        Connection connection = connection(preparedSql, 2, 2,
                List.of(List.of(new ChecksumRow(10, "digest-a", "digest-a"),
                        new ChecksumRow(20, "digest-b", "digest-b")), List.of()));

        ConsistencyVerifier.VerificationResult result = new ConsistencyVerifier().verify(
                connection, request(), List.of("id", "username"), 2);

        assertEquals(true, result.isConsistent(), "consistent result");
        assertEquals(2L, result.liveRowCount(), "live row count");
        assertEquals(2L, result.shadowRowCount(), "shadow row count");
        assertEquals(List.of(), result.mismatchedRowIds(), "mismatched ids");
        String pageSql = preparedSql.get(2);
        assertContains(pageSql, "WHERE \"id\" > ? UNION SELECT", "keyset pagination");
        assertContains(pageSql, "ORDER BY \"id\" ASC LIMIT ?", "bounded page query");
        assertContains(pageSql, "md5(CAST(ROW(live_row.\"id\", live_row.\"username\") AS text))",
                "live row checksum");
        assertContains(pageSql,
                "md5(CAST(ROW(shadow_row.\"id\", shadow_row.\"username\") AS text))",
                "shadow row checksum");
    }

    private static void reportsMismatchedRowsAndBlocksVerification() throws SQLException {
        List<List<ChecksumRow>> pages = List.of(List.of(
                new ChecksumRow(12, "same", "same"),
                new ChecksumRow(25, "live-digest", "shadow-digest")),
                List.of(new ChecksumRow(31, null, "shadow-only")), List.of());
        List<String> preparedSql = new ArrayList<>();
        Connection inspectConnection = connection(preparedSql, 3, 3, pages);

        ConsistencyVerifier.VerificationResult result = new ConsistencyVerifier().inspect(
                inspectConnection, request(), List.of("id", "username"), 2);

        assertEquals(false, result.isConsistent(), "inconsistent result");
        assertEquals(List.of(25L, 31L), result.mismatchedRowIds(), "mismatched ids");

        Connection verifyConnection = connection(new ArrayList<>(), 3, 3, pages);
        try {
            new ConsistencyVerifier().verify(
                    verifyConnection, request(), List.of("id", "username"), 2);
            throw new AssertionError("Expected verification failure for data drift");
        } catch (IllegalStateException expected) {
            assertContains(expected.getMessage(), "liveRowCount=3", "failure summary counts");
            assertContains(expected.getMessage(), "mismatchedRowIds=[25, 31]", "failure summary ids");
        }
    }

    private static Connection connection(List<String> preparedSql, long liveCount, long shadowCount,
                                         List<List<ChecksumRow>> pages) {
        InvocationHandler connectionHandler = (proxy, method, args) -> {
            if (!"prepareStatement".equals(method.getName())) {
                throw new UnsupportedOperationException(method.getName());
            }
            String sql = (String) args[0];
            preparedSql.add(sql);
            if (sql.startsWith("SELECT COUNT(*)")) {
                long count = sql.contains("_shadow_users") ? shadowCount : liveCount;
                return preparedStatement(null, count);
            }
            return preparedStatement(pages, 0);
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, connectionHandler);
    }

    private static PreparedStatement preparedStatement(List<List<ChecksumRow>> pages, long count) {
        int[] pageIndex = {0};
        InvocationHandler statementHandler = (proxy, method, args) -> switch (method.getName()) {
            case "setLong", "setInt", "close" -> null;
            case "executeQuery" -> {
                if (pages == null) {
                    yield countResultSet(count);
                }
                if (pageIndex[0] >= pages.size()) {
                    throw new SQLException("Unexpected extra checksum page");
                }
                yield checksumResultSet(pages.get(pageIndex[0]++));
            }
            default -> throw new UnsupportedOperationException(method.getName());
        };
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, statementHandler);
    }

    private static ResultSet countResultSet(long count) {
        InvocationHandler resultSetHandler = new InvocationHandler() {
            private boolean returned;

            @Override
            public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                return switch (method.getName()) {
                    case "next" -> !returned && (returned = true);
                    case "getLong" -> count;
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
        };
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, resultSetHandler);
    }

    private static ResultSet checksumResultSet(List<ChecksumRow> rows) {
        InvocationHandler resultSetHandler = new InvocationHandler() {
            private int index = -1;

            @Override
            public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                return switch (method.getName()) {
                    case "next" -> ++index < rows.size();
                    case "getLong" -> rows.get(index).id();
                    case "getString" -> switch ((String) args[0]) {
                        case "live_checksum" -> rows.get(index).liveChecksum();
                        case "shadow_checksum" -> rows.get(index).shadowChecksum();
                        default -> throw new UnsupportedOperationException((String) args[0]);
                    };
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
        };
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, resultSetHandler);
    }

    private static MigrationRequest request() {
        return DdlParser.parse("ALTER TABLE users ADD COLUMN phone VARCHAR(20)");
    }

    private static void assertContains(String actual, String expected, String description) {
        if (!actual.contains(expected)) {
            throw new AssertionError(description + ": expected <" + expected + "> in <" + actual + ">");
        }
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(description + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private record ChecksumRow(long id, String liveChecksum, String shadowChecksum) {
    }
}
