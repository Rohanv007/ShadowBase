package com.shadowbase.cli;

import com.shadowbase.config.DatabaseConfig;
import com.shadowbase.metrics.MigrationMetrics;
import com.shadowbase.migration.CutoverService;
import com.shadowbase.migration.MigrationService;
import com.shadowbase.parser.DdlParser;
import com.shadowbase.simulation.TrafficSimulator;

import java.sql.SQLException;
import java.util.Scanner;

public final class ShadowBaseCli implements AutoCloseable {
    private final DatabaseConfig databaseConfig;
    private final Scanner scanner;

    public ShadowBaseCli(DatabaseConfig databaseConfig) {
        this(databaseConfig, new Scanner(System.in));
    }

    ShadowBaseCli(DatabaseConfig databaseConfig, Scanner scanner) {
        if (databaseConfig == null) {
            throw new IllegalArgumentException("databaseConfig must not be null");
        }
        if (scanner == null) {
            throw new IllegalArgumentException("scanner must not be null");
        }
        this.databaseConfig = databaseConfig;
        this.scanner = scanner;
    }

    public void run() {
        boolean running = true;
        while (running) {
            printMenu();
            if (!scanner.hasNextLine()) {
                return;
            }
            String selection = scanner.nextLine().trim();
            try {
                switch (selection) {
                    case "1" -> validateDdl();
                    case "2" -> runMigration();
                    case "3" -> runMigrationWithTraffic();
                    case "4" -> rollbackCutover();
                    case "5" -> running = false;
                    default -> System.out.println("Choose an option from 1 to 5.");
                }
            } catch (SQLException | IllegalArgumentException | IllegalStateException failure) {
                System.out.println("Operation failed: " + failure.getMessage());
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                System.out.println("Operation interrupted; exiting the CLI.");
                return;
            }
        }
        System.out.println("ShadowBase CLI closed.");
    }

    @Override
    public void close() {
        scanner.close();
    }

    private void printMenu() {
        System.out.println();
        System.out.println("ShadowBase Interactive CLI");
        System.out.println("1. Validate DDL");
        System.out.println("2. Run Full Zero-Downtime Migration");
        System.out.println("3. Run Migration + Concurrent Traffic Simulation");
        System.out.println("4. Rollback Last Cutover");
        System.out.println("5. Exit");
        System.out.print("Select an option: ");
    }

    private void validateDdl() {
        String sql = prompt("ALTER TABLE SQL: ");
        var request = DdlParser.parse(sql);
        System.out.println("Parsed migration: " + request);
        System.out.println("Target shadow table: " + request.shadowTableName());
    }

    private void runMigration() throws SQLException, InterruptedException {
        String sql = prompt("ALTER TABLE SQL: ");
        MigrationMetrics metrics = new MigrationMetrics();
        try {
            MigrationService.MigrationResult result =
                    new MigrationService(databaseConfig).migrateWithCutover(sql, metrics);
            System.out.printf("Migration complete: %s promoted; %d rows copied in %d batches.%n",
                    result.tableName(), result.rowsCopied(), result.batches());
        } finally {
            System.out.println(metrics.formatSummaryReport());
        }
    }

    private void runMigrationWithTraffic() throws SQLException, InterruptedException {
        String sql = prompt("ALTER TABLE SQL: ");
        int writesPerSecond = readPositiveInteger("Writes per second: ");
        int workerCount = readPositiveInteger("Worker count: ");
        String liveTable = DdlParser.parse(sql).tableName();
        MigrationMetrics metrics = new MigrationMetrics();
        TrafficSimulator simulator = new TrafficSimulator(databaseConfig, metrics);
        try (simulator) {
            simulator.startSimulation(liveTable, writesPerSecond, workerCount);
            MigrationService.MigrationResult result =
                    new MigrationService(databaseConfig).migrateWithCutover(sql, metrics);
            System.out.printf("Migration complete: %s promoted; %d rows copied in %d batches.%n",
                    result.tableName(), result.rowsCopied(), result.batches());
        } finally {
            System.out.printf("Write simulation: %d inserts, %d updates, %d failed writes.%n",
                    simulator.getInsertedCount(), simulator.getUpdatedCount(),
                    simulator.getFailedWrites());
            System.out.println(metrics.formatSummaryReport());
        }
    }

    private void rollbackCutover() throws SQLException {
        String liveTable = prompt("Live table name: ");
        String archivedTable = prompt("Archived _old_ table name: ");
        String displacedTable = new CutoverService(databaseConfig)
                .rollbackCutover(liveTable, archivedTable);
        System.out.printf("Rollback complete: %s restored; previous live table retained as %s.%n",
                liveTable, displacedTable);
    }

    private String prompt(String message) {
        System.out.print(message);
        if (!scanner.hasNextLine()) {
            throw new IllegalStateException("Input ended before the operation was completed");
        }
        return scanner.nextLine().trim();
    }

    private int readPositiveInteger(String message) {
        String value = prompt(message);
        try {
            int parsed = Integer.parseInt(value);
            if (parsed > 0) {
                return parsed;
            }
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(message.trim() + " must be a positive integer",
                    exception);
        }
        throw new IllegalArgumentException(message.trim() + " must be a positive integer");
    }
}
