package com.shadowbase.migration;

import com.shadowbase.migration.BackfillWorker.BackfillProgress;
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

public final class BackfillWorkerTest {
    private BackfillWorkerTest() {
    }

    public static void main(String[] args) throws SQLException, InterruptedException {
        copiesKeysetPagesAndTracksProgress();
        mapsRenamedColumnsDuringBackfill();
        completesWhenTheSourceTableIsEmpty();
        System.out.println("BackfillWorkerTest passed");
    }

    private static void mapsRenamedColumnsDuringBackfill() throws SQLException, InterruptedException {
        List<String> preparedSql = new ArrayList<>();
        Connection connection = connection(preparedSql, new ArrayList<>(),
                List.of(List.of(1L), List.of()), List.of(1));
        MigrationRequest request = DdlParser.parse(
                "ALTER TABLE users RENAME COLUMN username TO handle");

        new BackfillWorker().backfill(connection, request, List.of("id", "username"), 10);

        assertContains(preparedSql.get(0), "SELECT \"id\" FROM users",
                "backfill keyset source");
        assertContains(preparedSql.get(1),
                "INSERT INTO _shadow_users (\"id\", \"handle\") SELECT \"id\", \"username\" "
                        + "FROM users",
                "source-to-shadow rename mapping");
    }

    private static void copiesKeysetPagesAndTracksProgress() throws SQLException, InterruptedException {
        List<String> preparedSql = new ArrayList<>();
        List<Long> pageAfterIds = new ArrayList<>();
        List<Integer> insertCounts = new ArrayList<>(List.of(1, 1));
        Connection connection = connection(preparedSql, pageAfterIds,
                List.of(List.of(10L, 20L), List.of(30L), List.of()), insertCounts);
        MigrationRequest request = DdlParser.parse(
                "ALTER TABLE public.users ADD COLUMN phone VARCHAR(20)");

        BackfillProgress progress = new BackfillWorker().backfill(connection, request,
                List.of("id", "username"), 2);

        assertEquals(30L, progress.lastProcessedId(), "last processed id");
        assertEquals(2L, progress.totalRowsCopied(), "total copied rows");
        assertEquals(2L, progress.batchCount(), "batch count");
        assertEquals(List.of(Long.MIN_VALUE, 20L, 30L), pageAfterIds, "keyset cursors");
        assertEquals(5, preparedSql.size(), "query count");

        String insertSql = preparedSql.get(1);
        assertContains(insertSql,
                "INSERT INTO public._shadow_users (\"id\", \"username\") SELECT \"id\", \"username\" "
                        + "FROM public.users WHERE \"id\" > ? ORDER BY \"id\" ASC LIMIT ? "
                        + "ON CONFLICT (\"id\") DO NOTHING",
                "backfill insert");
        if (insertSql.contains("OFFSET")) {
            throw new AssertionError("Backfill must not use OFFSET pagination");
        }
    }

    private static void completesWhenTheSourceTableIsEmpty() throws SQLException, InterruptedException {
        List<String> preparedSql = new ArrayList<>();
        List<Long> pageAfterIds = new ArrayList<>();
        Connection connection = connection(preparedSql, pageAfterIds,
                List.of(List.of()), new ArrayList<>());
        MigrationRequest request = DdlParser.parse(
                "ALTER TABLE users ADD COLUMN phone VARCHAR(20)");

        BackfillProgress progress = new BackfillWorker().backfill(connection, request,
                List.of("id", "username"), 100);

        assertEquals(null, progress.lastProcessedId(), "empty table last processed id");
        assertEquals(0L, progress.totalRowsCopied(), "empty table copied rows");
        assertEquals(0L, progress.batchCount(), "empty table batch count");
        assertEquals(1, preparedSql.size(), "empty table query count");
    }

    private static Connection connection(List<String> preparedSql, List<Long> pageAfterIds,
                                         List<List<Long>> pages, List<Integer> insertCounts) {
        int[] pageIndex = {0};
        int[] insertIndex = {0};
        InvocationHandler connectionHandler = (proxy, method, args) -> {
            if ("prepareStatement".equals(method.getName())) {
                String sql = (String) args[0];
                preparedSql.add(sql);
                InvocationHandler statementHandler = new InvocationHandler() {
                    private long afterId;

                    @Override
                    public Object invoke(Object statementProxy, java.lang.reflect.Method statementMethod,
                                         Object[] statementArgs) throws SQLException {
                        return switch (statementMethod.getName()) {
                            case "setLong" -> {
                                afterId = (long) statementArgs[1];
                                yield null;
                            }
                            case "setInt", "close" -> null;
                            case "executeQuery" -> {
                                pageAfterIds.add(afterId);
                                if (pageIndex[0] >= pages.size()) {
                                    throw new SQLException("Unexpected extra keyset page");
                                }
                                yield resultSet(pages.get(pageIndex[0]++));
                            }
                            case "executeUpdate" -> {
                                if (insertIndex[0] >= insertCounts.size()) {
                                    throw new SQLException("Unexpected extra insert batch");
                                }
                                yield insertCounts.get(insertIndex[0]++);
                            }
                            default -> throw new UnsupportedOperationException(statementMethod.getName());
                        };
                    }
                };
                return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                        new Class<?>[]{PreparedStatement.class}, statementHandler);
            }
            throw new UnsupportedOperationException(method.getName());
        };
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, connectionHandler);
    }

    private static ResultSet resultSet(List<Long> ids) {
        InvocationHandler resultSetHandler = new InvocationHandler() {
            private int index = -1;

            @Override
            public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
                return switch (method.getName()) {
                    case "next" -> ++index < ids.size();
                    case "getLong" -> ids.get(index);
                    case "close" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            }
        };
        return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, resultSetHandler);
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
}
