package com.rakesh.chat.server;

import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Shared test helpers.
 *
 * <p>{@link #await} exists so that no test in this project ever contains
 * {@code Thread.sleep(200)} as a synchronisation device. A sleep is a bet that the machine
 * is at least as fast as the one the test was written on; it is the single most common
 * source of suites that are green locally and flaky on CI. Polling for the condition is
 * both faster in the common case and honest about what it is waiting for — the failure
 * message names the condition rather than saying "timed out".
 *
 * <p>Sleeps that are testing <i>elapsed time itself</i> — a rate-limit window refilling, a
 * handshake deadline expiring — are a different thing and are legitimate.
 */
final class TestSupport {

    private static final long DEFAULT_TIMEOUT_NANOS = 5_000_000_000L;

    private TestSupport() {
    }

    static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + DEFAULT_TIMEOUT_NANOS;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(5);
        }
        fail("timed out waiting for: " + what);
    }
}
