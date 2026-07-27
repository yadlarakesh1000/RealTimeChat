package com.rakesh.chat.server;

/**
 * A token bucket, used to stop one client from flooding the server.
 *
 * <p>The idea: the bucket holds up to {@code capacity} tokens and starts full. Sending a
 * message costs one token. Tokens trickle back in over time, so the bucket refills to
 * full in {@code windowMillis}. If the bucket is empty, the message is refused.
 *
 * <p>Why a token bucket and not a "leaky bucket"? A leaky bucket forces a perfectly even
 * rate, like one message every 500 ms. Real people type in bursts — you paste three lines,
 * then say nothing for a minute — so a leaky bucket would tell a normal user to slow down.
 * A token bucket allows the burst and only complains if it keeps going.
 *
 * <p>Time is measured with {@link System#nanoTime()} and not {@code currentTimeMillis()},
 * because the wall clock can jump backwards (for example when the machine syncs its time).
 * A backwards jump would make "time elapsed" negative and hand out free tokens.
 */
public class TokenBucket {

    private final int capacity;
    private final double tokensPerNano;

    private double tokens;
    private long lastCheckNanos;

    /**
     * @param capacity     how many messages may be sent in one burst
     * @param windowMillis how long a full refill takes
     */
    public TokenBucket(int capacity, int windowMillis) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, was " + capacity);
        }
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("windowMillis must be positive, was " + windowMillis);
        }
        this.capacity = capacity;
        this.tokensPerNano = capacity / (windowMillis * 1_000_000.0);
        this.tokens = capacity;   // a brand new client may burst straight away
        this.lastCheckNanos = System.nanoTime();
    }

    /** Takes one token if there is one. Returns false if the client must be refused. */
    public synchronized boolean tryConsume() {
        refill();
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    /** How many whole messages are allowed right now. Used by tests. */
    public synchronized long availableTokens() {
        refill();
        return (long) tokens;
    }

    /** Adds the tokens earned since the last call, never going above the capacity. */
    private void refill() {
        long now = System.nanoTime();
        long elapsedNanos = now - lastCheckNanos;
        lastCheckNanos = now;
        if (elapsedNanos > 0) {
            tokens = Math.min(capacity, tokens + elapsedNanos * tokensPerNano);
        }
    }
}
