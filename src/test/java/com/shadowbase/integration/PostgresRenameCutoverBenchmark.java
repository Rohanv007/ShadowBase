package com.shadowbase.integration;

import com.shadowbase.config.DatabaseConfig;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.UUID;

public final class PostgresRenameCutoverBenchmark {
    private static final int ROW_COUNT = 50_000;
    private static final int WARMUP_ITERATIONS = 3;
    private static final int MEASURED_ITERATIONS = 20;

    private PostgresRenameCutoverBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        String base = "shadowbase_cutover_" + UUID.randomUUID().toString().replace("-", "");
        String live = quoteIdentifier(base);
        String shadow = quoteIdentifier("_shadow_" + base);
        String former = quoteIdentifier("_former_" + base);
        long[] latenciesMicros = new long[MEASURED_ITERATIONS];

        try (DatabaseConfig databaseConfig = new DatabaseConfig();
             Connection connection = databaseConfig.getConnection();
             Statement statement = connection.createStatement()) {
            if (!databaseConfig.testConnection()) {
                throw new IllegalStateException("PostgreSQL health check failed");
            }
            statement.execute("CREATE TABLE " + live + " (id BIGINT PRIMARY KEY, payload TEXT NOT NULL)");
            statement.execute("INSERT INTO " + live + " (id, payload) SELECT id, 'row-' || id "
                    + "FROM generate_series(1, " + ROW_COUNT + ") AS id");

            for (int iteration = 0;
                 iteration < WARMUP_ITERATIONS + MEASURED_ITERATIONS; iteration++) {
                statement.execute("CREATE TABLE " + shadow + " (LIKE " + live + " INCLUDING ALL)");
                statement.execute("INSERT INTO " + shadow + " SELECT * FROM " + live);

                long startedAt = System.nanoTime();
                connection.setAutoCommit(false);
                try {
                    statement.execute("SET LOCAL lock_timeout = '2s'");
                    statement.execute("LOCK TABLE " + live + ", " + shadow
                            + " IN ACCESS EXCLUSIVE MODE");
                    statement.execute("ALTER TABLE " + live + " RENAME TO " + quoteIdentifier(former));
                    statement.execute("ALTER TABLE " + shadow + " RENAME TO " + quoteIdentifier(base));
                    connection.commit();
                } catch (Exception failure) {
                    connection.rollback();
                    throw failure;
                } finally {
                    connection.setAutoCommit(true);
                }
                long elapsedNanos = System.nanoTime() - startedAt;
                if (iteration >= WARMUP_ITERATIONS) {
                    latenciesMicros[iteration - WARMUP_ITERATIONS] = elapsedNanos / 1_000;
                }
                statement.execute("DROP TABLE " + former);
            }

            Arrays.sort(latenciesMicros);
            System.out.printf("PostgreSQL table-rename cutover baseline (%d rows, %d samples): "
                            + "min=%dus p50=%dus p95=%dus max=%dus%n",
                    ROW_COUNT, MEASURED_ITERATIONS, latenciesMicros[0],
                    percentile(latenciesMicros, 0.50), percentile(latenciesMicros, 0.95),
                    latenciesMicros[latenciesMicros.length - 1]);
        } finally {
            try (DatabaseConfig databaseConfig = new DatabaseConfig();
                 Connection connection = databaseConfig.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS " + shadow);
                statement.execute("DROP TABLE IF EXISTS " + former);
                statement.execute("DROP TABLE IF EXISTS " + live);
            }
        }
    }

    private static long percentile(long[] sortedValues, double percentile) {
        int index = (int) Math.ceil(percentile * sortedValues.length) - 1;
        return sortedValues[Math.max(0, Math.min(index, sortedValues.length - 1))];
    }

    private static String quoteIdentifier(String identifier) {
        String unquoted = identifier.length() >= 2 && identifier.startsWith("\"")
                && identifier.endsWith("\"")
                ? identifier.substring(1, identifier.length() - 1).replace("\"\"", "\"")
                : identifier;
        return "\"" + unquoted.replace("\"", "\"\"") + "\"";
    }

}
