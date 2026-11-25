package com.shadowbase.simulation;

import com.shadowbase.config.DatabaseConfig;
import com.shadowbase.metrics.MigrationMetrics;
import com.shadowbase.parser.DdlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public final class TrafficSimulator implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(TrafficSimulator.class);
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 30;
    private static final long MAX_PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

    private final DatabaseConfig databaseConfig;
    private final MigrationMetrics metrics;
    private final AtomicLong insertedCount = new AtomicLong();
    private final AtomicLong updatedCount = new AtomicLong();
    private final AtomicLong failedWrites = new AtomicLong();
    private final AtomicLong nextWriteAtNanos = new AtomicLong();

    private volatile boolean running;
    private ExecutorService executor;

    public TrafficSimulator(DatabaseConfig databaseConfig, MigrationMetrics metrics) {
        this.databaseConfig = Objects.requireNonNull(databaseConfig,
                "databaseConfig must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    public synchronized void startSimulation(String tableName, int writesPerSecond,
                                             int workerCount) {
        if (running || executor != null) {
            throw new IllegalStateException("Traffic simulation is already running");
        }
        if (writesPerSecond <= 0) {
            throw new IllegalArgumentException("writesPerSecond must be positive");
        }
        if (workerCount <= 0) {
            throw new IllegalArgumentException("workerCount must be positive");
        }

        String validatedTableName = validateTableName(tableName);
        long intervalNanos = Math.max(1, TimeUnit.SECONDS.toNanos(1) / writesPerSecond);

        nextWriteAtNanos.set(0);
        running = true;
        executor = Executors.newFixedThreadPool(workerCount);
        for (int worker = 0; worker < workerCount; worker++) {
            executor.submit(() -> runWorker(validatedTableName, intervalNanos));
        }
        LOGGER.info("Started PostgreSQL traffic simulation for {} at {} writes/sec "
                        + "with {} workers",
                validatedTableName, writesPerSecond, workerCount);
    }

    public synchronized void stopSimulation() throws InterruptedException {
        if (executor == null) {
            return;
        }

        running = false;
        executor.shutdown();
        if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Traffic simulator workers did not stop in time");
            }
        }
        executor = null;
        LOGGER.info("Stopped PostgreSQL traffic simulation: inserted={}, updated={}, failed={}",
                insertedCount.get(), updatedCount.get(), failedWrites.get());
    }

    public long getInsertedCount() {
        return insertedCount.get();
    }

    public long getUpdatedCount() {
        return updatedCount.get();
    }

    public long getFailedWrites() {
        return failedWrites.get();
    }

    @Override
    public void close() throws InterruptedException {
        stopSimulation();
    }

    private void runWorker(String tableName, long writeIntervalNanos) {
        String insertSql = "INSERT INTO " + tableName
                + " (username, email) VALUES (?, ?) RETURNING id";
        String updateSql = "UPDATE " + tableName
                + " SET email = ? WHERE id = (SELECT id FROM " + tableName
                + " ORDER BY RANDOM() LIMIT 1)";

        while (running && !Thread.currentThread().isInterrupted()) {
            waitForWriteSlot(writeIntervalNanos);
            if (!running || Thread.currentThread().isInterrupted()) {
                return;
            }

            try {
                if (ThreadLocalRandom.current().nextBoolean()) {
                    insert(tableName, insertSql);
                } else {
                    update(updateSql);
                }
            } catch (SQLException failure) {
                failedWrites.incrementAndGet();
                metrics.addFailedSimulatedWrites(1);
                LOGGER.warn("Simulated write failed against {}", tableName, failure);
            }
        }
    }

    private void insert(String tableName, String sql) throws SQLException {
        String username = "traffic_" + UUID.randomUUID().toString().replace("-", "");
        String email = username + "@shadowbase.test";
        try (Connection connection = databaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            statement.setString(2, email);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Insert did not return the generated user id");
                }
            }
        }
        insertedCount.incrementAndGet();
        metrics.addSimulatedInserts(1);
    }

    private void update(String sql) throws SQLException {
        String email = "traffic_" + UUID.randomUUID().toString().replace("-", "")
                + "@shadowbase.test";
        int affectedRows;
        try (Connection connection = databaseConfig.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, email);
            affectedRows = statement.executeUpdate();
        }
        if (affectedRows == 0) {
            failedWrites.incrementAndGet();
            metrics.addFailedSimulatedWrites(1);
            LOGGER.warn("Simulated update found no row to update");
            return;
        }
        updatedCount.incrementAndGet();
        metrics.addSimulatedUpdates(1);
    }

    private void waitForWriteSlot(long writeIntervalNanos) {
        long scheduledAtNanos = reserveWriteSlot(writeIntervalNanos);
        long remainingNanos = scheduledAtNanos - System.nanoTime();
        while (running && remainingNanos > 0) {
            LockSupport.parkNanos(Math.min(remainingNanos, MAX_PARK_NANOS));
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            remainingNanos = scheduledAtNanos - System.nanoTime();
        }
    }

    private long reserveWriteSlot(long writeIntervalNanos) {
        while (true) {
            long now = System.nanoTime();
            long previous = nextWriteAtNanos.get();
            long scheduled = Math.max(previous, now) + writeIntervalNanos;
            if (nextWriteAtNanos.compareAndSet(previous, scheduled)) {
                return scheduled;
            }
        }
    }

    private static String validateTableName(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            throw new IllegalArgumentException("tableName must not be null or blank");
        }
        return DdlParser.parse("ALTER TABLE " + tableName
                + " ADD COLUMN _traffic_simulator_probe INTEGER").tableName();
    }
}
