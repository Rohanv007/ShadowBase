package com.shadowbase.migration;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class ThrottleController {
    public static final int DEFAULT_BATCH_SIZE = 1_000;
    public static final int MIN_BATCH_SIZE = 100;
    public static final int MAX_BATCH_SIZE = 10_000;
    public static final long DEFAULT_SLEEP_DELAY_MS = 50;

    private final AtomicInteger currentBatchSize = new AtomicInteger(DEFAULT_BATCH_SIZE);
    private final AtomicLong sleepDelayMs = new AtomicLong(DEFAULT_SLEEP_DELAY_MS);
    private final Object pauseMonitor = new Object();
    private volatile boolean paused;

    public int getCurrentBatchSize() {
        return currentBatchSize.get();
    }

    public long getSleepDelayMs() {
        return sleepDelayMs.get();
    }

    public boolean isPaused() {
        return paused;
    }

    public void pause() {
        synchronized (pauseMonitor) {
            paused = true;
        }
    }

    public void resume() {
        synchronized (pauseMonitor) {
            paused = false;
            pauseMonitor.notifyAll();
        }
    }

    public void waitIfPaused() throws InterruptedException {
        synchronized (pauseMonitor) {
            while (paused) {
                try {
                    pauseMonitor.wait();
                } catch (InterruptedException interruption) {
                    Thread.currentThread().interrupt();
                    throw interruption;
                }
            }
        }
    }

    public void adjustForLatency(long lastBatchDurationMs, long targetBatchLatencyMs) {
        if (lastBatchDurationMs < 0) {
            throw new IllegalArgumentException("lastBatchDurationMs must not be negative");
        }
        if (targetBatchLatencyMs <= 0) {
            throw new IllegalArgumentException("targetBatchLatencyMs must be positive");
        }

        if (lastBatchDurationMs > targetBatchLatencyMs) {
            currentBatchSize.updateAndGet(size -> Math.max(MIN_BATCH_SIZE, size * 4 / 5));
            sleepDelayMs.updateAndGet(delay -> delay > Long.MAX_VALUE - 10
                    ? Long.MAX_VALUE : delay + 10);
        } else if (lastBatchDurationMs < targetBatchLatencyMs * 0.8) {
            currentBatchSize.updateAndGet(size -> Math.min(MAX_BATCH_SIZE,
                    size + Math.max(1, size / 10)));
            sleepDelayMs.updateAndGet(delay -> Math.max(0, delay - Math.max(1, delay / 10)));
        }
    }
}
