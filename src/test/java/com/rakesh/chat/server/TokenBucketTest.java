package com.rakesh.chat.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rate limiting is a security control, and an untested security control is a comment.
 *
 * <p>These tests are deliberately timing-based, because the thing under test <i>is</i>
 * timing. They are kept honest by choosing windows short enough to finish fast and
 * asserting on inequalities with slack rather than on exact counts — an assertion like
 * "exactly 7 tokens after 350 ms" would fail on a loaded CI box for reasons that have
 * nothing to do with the code.
 */
@Timeout(20)
class TokenBucketTest {

    @Nested
    @DisplayName("burst")
    class Burst {

        @Test
        @DisplayName("a fresh bucket allows a full burst and then refuses")
        void startsFull() {
            TokenBucket bucket = new TokenBucket(5, 10_000);

            for (int i = 0; i < 5; i++) {
                assertTrue(bucket.tryConsume(), "token " + (i + 1) + " should be available");
            }
            assertFalse(bucket.tryConsume(), "the 6th token must be refused");
        }

        @Test
        @DisplayName("availableTokens reports the burst without consuming it")
        void availableTokensIsNonDestructive() {
            TokenBucket bucket = new TokenBucket(8, 10_000);

            assertEquals(8, bucket.availableTokens());
            assertEquals(8, bucket.availableTokens(), "inspection must not spend tokens");

            assertTrue(bucket.tryConsume());
            assertEquals(7, bucket.availableTokens());
        }
    }

    @Nested
    @DisplayName("refill")
    class Refill {

        @Test
        @DisplayName("credit comes back over the window, not all at the end")
        void refillsGradually() throws InterruptedException {
            // 10 tokens per 500 ms => one token per 50 ms.
            TokenBucket bucket = new TokenBucket(10, 500);
            drain(bucket);
            assertEquals(0, bucket.availableTokens());

            Thread.sleep(150);

            long available = bucket.availableTokens();
            // ~3 expected. Lower bound 1 tolerates a slow scheduler; upper bound 9 is the
            // assertion that actually matters — it proves the refill is proportional to
            // elapsed time and not "the window passed, have everything".
            assertTrue(available >= 1 && available <= 9,
                    "expected a partial refill after 150 ms of a 500 ms window, got " + available);
        }

        @Test
        @DisplayName("a full window restores exactly the capacity, never more")
        void refillIsCappedAtCapacity() throws InterruptedException {
            TokenBucket bucket = new TokenBucket(4, 200);
            drain(bucket);

            Thread.sleep(1000); // five full windows

            assertEquals(4, bucket.availableTokens(),
                    "idle time must not accumulate credit beyond the capacity");
        }

        @Test
        @DisplayName("hammering an empty bucket does not starve the refill")
        void frequentPollingStillRefills() throws InterruptedException {
            // The regression this exists for: an implementation that advances its
            // "last refill" timestamp on every call, but computes the refill with integer
            // truncation, adds zero each time and never recovers. It only shows up when
            // the bucket is polled far faster than one token's worth of time.
            TokenBucket bucket = new TokenBucket(5, 400);
            drain(bucket);

            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            int granted = 0;
            while (System.nanoTime() < deadline) {
                if (bucket.tryConsume()) {
                    granted++;
                }
                // no sleep: poll as hard as the CPU allows
            }

            assertTrue(granted >= 3,
                    "a busy-polled bucket must still refill; granted only " + granted);
        }
    }

    @Nested
    @DisplayName("sustained rate")
    class SustainedRate {

        @Test
        @DisplayName("a flooder gets the burst plus the refill, and nothing else")
        void floodIsClamped() throws InterruptedException {
            TokenBucket bucket = new TokenBucket(10, 1000); // 10 per second
            int granted = 0;

            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
            while (System.nanoTime() < deadline) {
                if (bucket.tryConsume()) {
                    granted++;
                }
                Thread.sleep(1);
            }

            // 10 burst + ~5 refilled over 500 ms. The ceiling is the point: an unlimited
            // sender got at most ~16 through where it attempted ~500.
            assertTrue(granted >= 10, "the initial burst must be honoured, got " + granted);
            assertTrue(granted <= 20, "sustained rate not enforced: " + granted + " in 500 ms");
        }
    }

    @Nested
    @DisplayName("contract")
    class Contract {

        @Test
        void rejectsNonsenseConstructorArguments() {
            assertThrows(IllegalArgumentException.class, () -> new TokenBucket(0, 1000));
            assertThrows(IllegalArgumentException.class, () -> new TokenBucket(-1, 1000));
            assertThrows(IllegalArgumentException.class, () -> new TokenBucket(10, 0));
            assertThrows(IllegalArgumentException.class, () -> new TokenBucket(10, -1));
        }

        @Test
        @DisplayName("a capacity larger than the window in nanos still works")
        void degenerateCostRoundsToOneNano() {
            // capacity so large that windowNanos / capacity would truncate to 0.
            TokenBucket bucket = new TokenBucket(Integer.MAX_VALUE, 1);
            assertTrue(bucket.tryConsume(), "cost must be clamped to at least 1 nanosecond");
        }

        @Test
        @DisplayName("concurrent consumers never over-issue")
        void isThreadSafe() throws InterruptedException {
            int capacity = 100;
            // A long window so no meaningful refill happens during the test: every grant
            // must therefore come out of the initial capacity.
            TokenBucket bucket = new TokenBucket(capacity, 600_000);

            int threads = 16;
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger granted = new AtomicInteger();

            for (int t = 0; t < threads; t++) {
                pool.execute(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < 100; i++) {
                            if (bucket.tryConsume()) {
                                granted.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertTrue(done.await(15, TimeUnit.SECONDS));
            pool.shutdownNow();

            // 1600 attempts against 100 tokens. Without the lock, the read-modify-write of
            // creditNanos would let two threads spend the same credit.
            assertEquals(capacity, granted.get(),
                    "exactly the capacity must be issued across all threads");
        }
    }

    private static void drain(TokenBucket bucket) {
        while (bucket.tryConsume()) {
            // spend everything
        }
    }
}
