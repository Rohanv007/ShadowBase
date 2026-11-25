package com.shadowbase.metrics;

public final class MigrationMetricsTest {
    private MigrationMetricsTest() {
    }

    public static void main(String[] args) throws InterruptedException {
        recordsLifecycleCountersAndSummary();
        measuresCutoverPause();
        System.out.println("MigrationMetricsTest passed");
    }

    private static void recordsLifecycleCountersAndSummary() throws InterruptedException {
        MigrationMetrics metrics = new MigrationMetrics();
        metrics.transitionTo(MigrationPhase.BACKFILLING);
        metrics.addBackfilledRows(200);
        metrics.addProcessedBatches(4);
        metrics.addSimulatedInserts(3);
        metrics.addSimulatedUpdates(2);
        metrics.addFailedSimulatedWrites(1);
        Thread.sleep(2);
        metrics.transitionTo(MigrationPhase.VERIFYING);
        metrics.addVerificationMismatches(1);
        metrics.complete();

        assertEquals(MigrationPhase.COMPLETED, metrics.getCurrentPhase(), "terminal phase");
        assertNotNull(metrics.getStartedAt(), "start timestamp");
        assertNotNull(metrics.getEndedAt(), "end timestamp");
        assertEquals(200L, metrics.getRowsBackfilled(), "backfilled rows");
        assertEquals(4L, metrics.getBatchesProcessed(), "processed batches");
        assertEquals(1L, metrics.getVerificationMismatches(), "verification mismatches");
        assertEquals(3L, metrics.getSimulatedInserts(), "simulated inserts");
        assertEquals(2L, metrics.getSimulatedUpdates(), "simulated updates");
        assertEquals(1L, metrics.getFailedSimulatedWrites(), "failed simulated writes");
        assertContains(metrics.formatSummaryReport(), "200 rows in 4 batches", "backfill summary");
        assertContains(metrics.formatSummaryReport(),
                "Simulated traffic: 3 inserts, 2 updates, 1 failed writes",
                "simulated traffic summary");
        assertContains(metrics.formatSummaryReport(), "Cutover pause: not measured",
                "unperformed cutover");
        if (metrics.getBackfillRowsPerSecond() <= 0.0) {
            throw new AssertionError("Backfill throughput should be positive");
        }
    }

    private static void measuresCutoverPause() throws InterruptedException {
        MigrationMetrics metrics = new MigrationMetrics();
        metrics.measureCutoverLock(() -> {
            try {
                Thread.sleep(5);
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interruption);
            }
        });

        assertEquals(MigrationPhase.CUTOVER, metrics.getCurrentPhase(), "cutover phase");
        if (metrics.getCutoverLockDurationMs() < 1) {
            throw new AssertionError("Cutover duration should include the measured pause");
        }
        assertContains(metrics.formatSummaryReport(),
                "Cutover pause: " + metrics.getCutoverLockDurationMs() + " ms",
                "cutover summary");
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

    private static void assertNotNull(Object actual, String description) {
        if (actual == null) {
            throw new AssertionError(description + " must not be null");
        }
    }
}
