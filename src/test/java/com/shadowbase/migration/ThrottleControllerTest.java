package com.shadowbase.migration;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ThrottleControllerTest {
    private ThrottleControllerTest() {
    }

    public static void main(String[] args) throws InterruptedException {
        adjustsBatchSizeAndDelayForLatency();
        pauseBlocksUntilResume();
        System.out.println("ThrottleControllerTest passed");
    }

    private static void adjustsBatchSizeAndDelayForLatency() {
        ThrottleController controller = new ThrottleController();
        assertEquals(1_000, controller.getCurrentBatchSize(), "default batch size");
        assertEquals(50L, controller.getSleepDelayMs(), "default sleep delay");

        controller.adjustForLatency(600, 500);
        assertEquals(800, controller.getCurrentBatchSize(), "reduced batch size");
        assertEquals(60L, controller.getSleepDelayMs(), "increased sleep delay");

        for (int i = 0; i < 100; i++) {
            controller.adjustForLatency(0, 500);
        }
        assertEquals(ThrottleController.MAX_BATCH_SIZE, controller.getCurrentBatchSize(),
                "maximum batch size");
        assertEquals(0L, controller.getSleepDelayMs(), "minimum sleep delay");

        for (int i = 0; i < 100; i++) {
            controller.adjustForLatency(10_000, 500);
        }
        assertEquals(ThrottleController.MIN_BATCH_SIZE, controller.getCurrentBatchSize(),
                "minimum batch size");
    }

    private static void pauseBlocksUntilResume() throws InterruptedException {
        ThrottleController controller = new ThrottleController();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean passedCheckpoint = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            started.countDown();
            try {
                controller.waitIfPaused();
                passedCheckpoint.set(true);
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
            }
        });

        controller.pause();
        worker.start();
        if (!started.await(2, TimeUnit.SECONDS)) {
            throw new AssertionError("worker did not reach pause checkpoint");
        }
        Thread.sleep(25);
        assertEquals(false, passedCheckpoint.get(), "paused worker state");
        controller.resume();
        worker.join(2_000);
        if (worker.isAlive()) {
            throw new AssertionError("worker did not resume");
        }
        assertEquals(true, passedCheckpoint.get(), "resumed worker state");
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!expected.equals(actual)) {
            throw new AssertionError(description + ": expected <" + expected + "> but was <"
                    + actual + ">");
        }
    }
}
