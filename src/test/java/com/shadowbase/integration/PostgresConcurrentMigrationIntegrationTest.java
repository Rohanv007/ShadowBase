package com.shadowbase.integration;

import com.shadowbase.config.DatabaseConfig;
import com.shadowbase.migration.MigrationService;
import com.shadowbase.migration.MigrationService.MigrationResult;
import com.shadowbase.migration.TriggerGeneratorService;
import com.shadowbase.model.MigrationRequest;
import com.shadowbase.parser.DdlParser;
import com.shadowbase.verification.ConsistencyVerifier;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class PostgresConcurrentMigrationIntegrationTest {
    private static final int INITIAL_ROWS = 30_000;
    private static final int WRITER_ITERATIONS = 250;

    private PostgresConcurrentMigrationIntegrationTest() {
    }

    public static void main(String[] args) throws Exception {
        String table = "shadowbase_it_" + UUID.randomUUID().toString().replace("-", "");
        String quotedTable = quoteIdentifier(table);
        String alterSql = "ALTER TABLE " + quotedTable
                + " ADD COLUMN migration_marker VARCHAR(32)";
        MigrationRequest request = DdlParser.parse(alterSql);
        AtomicBoolean readersRunning = new AtomicBoolean(true);
        AtomicLong successfulWrites = new AtomicLong();
        AtomicLong successfulReads = new AtomicLong();
        ExecutorService workers = Executors.newFixedThreadPool(6);
        boolean tableCreated = false;

        try (DatabaseConfig databaseConfig = new DatabaseConfig()) {
            if (!databaseConfig.testConnection()) {
                throw new IllegalStateException("PostgreSQL health check failed");
            }
            createSourceTable(databaseConfig, quotedTable);
            tableCreated = true;

            CountDownLatch ready = new CountDownLatch(6);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> writers = new ArrayList<>();
            for (int workerId = 0; workerId < 3; workerId++) {
                int id = workerId;
                writers.add(workers.submit(() -> {
                    ready.countDown();
                    start.await();
                    runWriter(databaseConfig, quotedTable, id, successfulWrites);
                    return null;
                }));
            }
            List<Future<?>> readers = new ArrayList<>();
            for (int readerId = 0; readerId < 2; readerId++) {
                readers.add(workers.submit(() -> {
                    ready.countDown();
                    start.await();
                    runReader(databaseConfig, quotedTable, readersRunning, successfulReads);
                    return null;
                }));
            }
            Future<MigrationResult> migration = workers.submit(() -> {
                ready.countDown();
                start.await();
                return new MigrationService(databaseConfig, 500).migrate(alterSql);
            });

            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent workers did not become ready");
            }
            start.countDown();
            MigrationResult result = migration.get(3, TimeUnit.MINUTES);
            for (Future<?> writer : writers) {
                writer.get(30, TimeUnit.SECONDS);
            }
            readersRunning.set(false);
            for (Future<?> reader : readers) {
                reader.get(30, TimeUnit.SECONDS);
            }
            try (Connection connection = databaseConfig.getConnection()) {
                new ConsistencyVerifier().verify(connection, request,
                        List.of("id", "payload"), databaseConfig.getMigrationBatchSize());
            }

            if (!result.consistent()) {
                throw new AssertionError("Migration did not report consistent data: " + result);
            }
            if (successfulWrites.get() == 0 || successfulReads.get() == 0) {
                throw new AssertionError("Concurrent traffic did not exercise both reads and writes");
            }
            System.out.printf("PostgreSQL concurrent migration passed: rowsCopied=%d, "
                            + "batches=%d, reads=%d, writes=%d, elapsedMs=%d%n",
                    result.rowsCopied(), result.batches(), successfulReads.get(),
                    successfulWrites.get(), result.elapsedMillis());
        } finally {
            readersRunning.set(false);
            workers.shutdownNow();
            boolean workersStopped = workers.awaitTermination(10, TimeUnit.SECONDS);
            if (tableCreated) {
                cleanup(table, request);
            }
            if (!workersStopped) {
                throw new IllegalStateException("Concurrent workers did not stop in time");
            }
        }
    }

    private static void createSourceTable(DatabaseConfig databaseConfig, String table)
            throws Exception {
        try (Connection connection = databaseConfig.getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("CREATE TABLE " + table
                    + " (id BIGINT PRIMARY KEY, payload TEXT NOT NULL)");
            statement.execute("INSERT INTO " + table
                    + " (id, payload) SELECT id, 'initial-' || id "
                    + "FROM generate_series(1, " + INITIAL_ROWS + ") AS id");
            connection.commit();
        }
    }

    private static void runWriter(DatabaseConfig databaseConfig, String table, int workerId,
                                  AtomicLong successfulWrites) throws Exception {
        try (Connection connection = databaseConfig.getConnection();
             PreparedStatement update = connection.prepareStatement(
                     "UPDATE " + table + " SET payload = ? WHERE id = ?");
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO " + table + " (id, payload) VALUES (?, ?)");
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM " + table + " WHERE id = ?")) {
            for (int iteration = 0; iteration < WRITER_ITERATIONS; iteration++) {
                long existingId = 1L + (workerId * 701L + iteration) % INITIAL_ROWS;
                long insertedId = 100_000L + workerId * WRITER_ITERATIONS + iteration;

                update.setString(1, "updated-" + workerId + "-" + iteration);
                update.setLong(2, existingId);
                update.executeUpdate();
                successfulWrites.incrementAndGet();

                insert.setLong(1, insertedId);
                insert.setString(2, "inserted-" + workerId + "-" + iteration);
                insert.executeUpdate();
                successfulWrites.incrementAndGet();

                if (iteration > 0) {
                    delete.setLong(1, insertedId - 1);
                    delete.executeUpdate();
                    successfulWrites.incrementAndGet();
                }
                Thread.sleep(2);
            }
        }
    }

    private static void runReader(DatabaseConfig databaseConfig, String table,
                                  AtomicBoolean running, AtomicLong successfulReads)
            throws Exception {
        try (Connection connection = databaseConfig.getConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT COUNT(*) FROM " + table)) {
            while (running.get()) {
                try (var results = query.executeQuery()) {
                    if (!results.next()) {
                        throw new IllegalStateException("Concurrent read returned no result");
                    }
                    results.getLong(1);
                    successfulReads.incrementAndGet();
                }
                Thread.sleep(10);
            }
        }
    }

    private static void cleanup(String table, MigrationRequest request) throws Exception {
        try (DatabaseConfig databaseConfig = new DatabaseConfig();
             Connection connection = databaseConfig.getConnection();
             Statement statement = connection.createStatement()) {
            new TriggerGeneratorService().dropDualWriteTriggers(connection, request);
            statement.execute("DROP TABLE IF EXISTS " + request.shadowTableName());
            statement.execute("DROP TABLE IF EXISTS " + quoteIdentifier(table));
        }
    }

    private static String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
