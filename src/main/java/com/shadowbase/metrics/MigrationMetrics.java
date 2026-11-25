package com.shadowbase.metrics;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class MigrationMetrics {
    private final Instant startedAt = Instant.now();
    private final long startedAtNanos = System.nanoTime();
    private final AtomicReference<MigrationPhase> currentPhase =
            new AtomicReference<>(MigrationPhase.PROVISIONING);
    private final AtomicLong rowsBackfilled = new AtomicLong();
    private final AtomicLong batchesProcessed = new AtomicLong();
    private final AtomicLong verificationMismatches = new AtomicLong();
    private final AtomicLong cutoverLockDurationMs = new AtomicLong();
    private final AtomicLong simulatedInserts = new AtomicLong();
    private final AtomicLong simulatedUpdates = new AtomicLong();
    private final AtomicLong failedSimulatedWrites = new AtomicLong();
    private final AtomicLong backfillStartedAtNanos = new AtomicLong();
    private final AtomicLong backfillDurationNanos = new AtomicLong();
    private volatile boolean cutoverLockDurationMeasured;
    private volatile long endedAtNanos;
    private volatile Instant endedAt;

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public MigrationPhase getCurrentPhase() {
        return currentPhase.get();
    }

    public long getRowsBackfilled() {
        return rowsBackfilled.get();
    }

    public long getBatchesProcessed() {
        return batchesProcessed.get();
    }

    public long getVerificationMismatches() {
        return verificationMismatches.get();
    }

    public long getCutoverLockDurationMs() {
        return cutoverLockDurationMs.get();
    }

    public long getSimulatedInserts() {
        return simulatedInserts.get();
    }

    public long getSimulatedUpdates() {
        return simulatedUpdates.get();
    }

    public long getFailedSimulatedWrites() {
        return failedSimulatedWrites.get();
    }

    public synchronized void transitionTo(MigrationPhase phase) {
        Objects.requireNonNull(phase, "phase must not be null");
        if (endedAt != null) {
            throw new IllegalStateException("Migration metrics are already complete");
        }

        finishBackfillTiming();
        currentPhase.set(phase);
        if (phase == MigrationPhase.BACKFILLING) {
            backfillStartedAtNanos.set(System.nanoTime());
        }
    }

    public void addBackfilledRows(long count) {
        addNonNegative(rowsBackfilled, count, "count");
    }

    public void addProcessedBatches(long count) {
        addNonNegative(batchesProcessed, count, "count");
    }

    public void addVerificationMismatches(long count) {
        addNonNegative(verificationMismatches, count, "count");
    }

    public void addSimulatedInserts(long count) {
        addNonNegative(simulatedInserts, count, "count");
    }

    public void addSimulatedUpdates(long count) {
        addNonNegative(simulatedUpdates, count, "count");
    }

    public void addFailedSimulatedWrites(long count) {
        addNonNegative(failedSimulatedWrites, count, "count");
    }

    public void measureCutoverLock(Runnable lockHeldSwap) {
        Objects.requireNonNull(lockHeldSwap, "lockHeldSwap must not be null");
        transitionTo(MigrationPhase.CUTOVER);
        long lockHeldAtNanos = System.nanoTime();
        try {
            lockHeldSwap.run();
        } finally {
            long elapsedNanos = System.nanoTime() - lockHeldAtNanos;
            cutoverLockDurationMs.set(TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
            cutoverLockDurationMeasured = true;
        }
    }

    public synchronized void complete() {
        finish(MigrationPhase.COMPLETED);
    }

    public synchronized void markRolledBack() {
        finish(MigrationPhase.ROLLED_BACK);
    }

    public synchronized void fail() {
        finish(MigrationPhase.FAILED);
    }

    public long getTotalDurationMillis() {
        long finishNanos = endedAtNanos == 0 ? System.nanoTime() : endedAtNanos;
        return TimeUnit.NANOSECONDS.toMillis(finishNanos - startedAtNanos);
    }

    public double getBackfillRowsPerSecond() {
        long durationNanos = backfillDurationNanos.get();
        if (currentPhase.get() == MigrationPhase.BACKFILLING) {
            long backfillStart = backfillStartedAtNanos.get();
            if (backfillStart != 0) {
                durationNanos = System.nanoTime() - backfillStart;
            }
        }
        if (durationNanos <= 0) {
            return 0.0;
        }
        return rowsBackfilled.get() * 1_000_000_000.0 / durationNanos;
    }

    public String formatSummaryReport() {
        String cutoverStatus = cutoverLockDurationMeasured
                ? cutoverLockDurationMs.get() + " ms"
                : "not measured";
        return String.format(Locale.ROOT,
                "Migration benchmark summary%n"
                        + "Phase: %s%n"
                        + "Total duration: %d ms%n"
                        + "Backfill: %d rows in %d batches (%.2f rows/sec)%n"
                        + "Verification mismatches: %d%n"
                        + "Simulated traffic: %d inserts, %d updates, %d failed writes%n"
                        + "Cutover pause: %s",
                currentPhase.get(), getTotalDurationMillis(), rowsBackfilled.get(),
                batchesProcessed.get(), getBackfillRowsPerSecond(),
                verificationMismatches.get(), simulatedInserts.get(), simulatedUpdates.get(),
                failedSimulatedWrites.get(), cutoverStatus);
    }

    private synchronized void finish(MigrationPhase terminalPhase) {
        if (endedAt != null) {
            throw new IllegalStateException("Migration metrics are already complete");
        }
        finishBackfillTiming();
        endedAtNanos = System.nanoTime();
        endedAt = Instant.now();
        currentPhase.set(terminalPhase);
    }

    private void finishBackfillTiming() {
        long backfillStart = backfillStartedAtNanos.getAndSet(0);
        if (backfillStart != 0) {
            backfillDurationNanos.addAndGet(System.nanoTime() - backfillStart);
        }
    }

    private static void addNonNegative(AtomicLong counter, long count, String parameterName) {
        if (count < 0) {
            throw new IllegalArgumentException(parameterName + " must not be negative");
        }
        counter.addAndGet(count);
    }
}
