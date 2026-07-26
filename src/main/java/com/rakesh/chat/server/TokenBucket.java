package com.rakesh.chat.server;

import java.util.concurrent.TimeUnit;

/**
 * A token bucket: {@code capacity} operations may happen at once, and the allowance
 * refills smoothly to full over {@code window}.
 *
 * <p><b>Why token bucket and not leaky bucket.</b> A leaky bucket enforces a strictly
 * even output rate — one message per second, no exceptions. That is right for protecting
 * a downstream system with a fixed service rate. Chat is not that: real typing is bursty,
 * and a user who pastes three lines in a row is behaving normally. A token bucket permits
 * a burst up to {@code capacity} and then degrades to the sustained rate, which is
 * exactly the shape of legitimate traffic. Leaky bucket would rate-limit a normal human.
 *
 * <h2>Arithmetic, and why there is no floating point here</h2>
 *
 * The obvious implementation stores {@code double tokens} and adds
 * {@code elapsed * rate} on every call. It has two defects:
 *
 * <ol>
 *   <li><b>Truncation starvation.</b> With integer tokens and a client calling every few
 *       microseconds, each refill computes to zero, but {@code lastRefill} is advanced
 *       anyway — so the bucket never refills at all. The bug only appears under exactly
 *       the load the limiter exists for.</li>
 *   <li><b>Drift.</b> Repeated {@code double} accumulation is not associative; two buckets
 *       given identical inputs can disagree.</li>
 * </ol>
 *
 * <p>Both vanish if the unit of credit is <i>time</i> rather than a scaled count. This
 * class stores {@code creditNanos}, capped at {@code windowNanos}; elapsed nanoseconds
 * are added one-for-one, and one message costs {@code windowNanos / capacity} nanos.
 * Exact integer arithmetic, no remainder to lose, no scaling factor to pick.
 *
 * <h2>The clock</h2>
 *
 * {@link System#nanoTime()}, never {@code currentTimeMillis()}. Wall-clock time can jump
 * backwards — NTP correction, a VM resuming from a snapshot, a user fixing the timezone.
 * A backwards jump on a wall clock makes {@code elapsed} negative and hands out unlimited
 * credit, which is a rate limiter that stops limiting at the exact moment infrastructure
 * is misbehaving. {@code nanoTime} is monotonic and has no meaning except as a difference,
 * which is all a limiter ever needs.
 *
 * <h2>Thread safety</h2>
 *
 * Every method is {@code synchronized}. In this server only the owning reader thread
 * calls {@link #tryConsume()}, so the lock is always uncontended and costs a biased/thin
 * lock acquisition — but "currently only one thread touches it" is a property of the
 * caller, not of this class, and the next caller will not read this sentence.
 */
public final class TokenBucket {

    private final long windowNanos;
    private final long costNanos;

    /** Available credit, in nanoseconds of elapsed time. Range {@code [0, windowNanos]}. */
    private long creditNanos;
    private long lastNanos;

    /**
     * @param capacity     how many operations may burst at once, and how many refill per window
     * @param windowMillis the refill window
     */
    public TokenBucket(int capacity, int windowMillis) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, was " + capacity);
        }
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("windowMillis must be positive, was " + windowMillis);
        }
        this.windowNanos = TimeUnit.MILLISECONDS.toNanos(windowMillis);
        this.costNanos = Math.max(1, windowNanos / capacity);
        this.creditNanos = windowNanos; // buckets start full: a new client may burst immediately
        this.lastNanos = System.nanoTime();
    }

    /**
     * Take one token if one is available.
     *
     * @return {@code true} if the operation is allowed; {@code false} if it must be
     *         refused. Never blocks and never sleeps — a rate limiter that blocks has
     *         turned a rejection into a thread leak.
     */
    public synchronized boolean tryConsume() {
        refill(System.nanoTime());
        if (creditNanos >= costNanos) {
            creditNanos -= costNanos;
            return true;
        }
        return false;
    }

    /** How many whole operations are allowed right now. Diagnostics and tests only. */
    public synchronized long availableTokens() {
        refill(System.nanoTime());
        return creditNanos / costNanos;
    }

    private void refill(long now) {
        long elapsed = now - lastNanos;
        lastNanos = now;
        if (elapsed <= 0) {
            // nanoTime is monotonic, so this is either a same-nanosecond call or the
            // documented coarse-resolution case. Either way: no credit, no harm.
            return;
        }
        // Clamped before the add, so creditNanos + elapsed cannot overflow after a long idle.
        creditNanos = (elapsed >= windowNanos)
                ? windowNanos
                : Math.min(windowNanos, creditNanos + elapsed);
    }
}
