package com.aaa.collector.common.retry;

import java.time.Duration;

/**
 * Stateless utility for computing exponential backoff delays.
 *
 * <p>Delay is calculated as {@code baseDelayMs * 2^attempt}, capped at {@value #MAX_DELAY_MS} ms
 * (60 seconds). Thread-safe; no shared mutable state.
 */
public final class ExponentialBackoff {

    private static final long MAX_DELAY_MS = 60_000;

    private ExponentialBackoff() {}

    /**
     * Returns the backoff delay for the given attempt.
     *
     * @param attempt 0-based attempt index (0 = first retry)
     * @param baseDelayMs base delay in milliseconds; must be positive
     * @return delay duration, capped at 60 seconds
     * @throws IllegalArgumentException if {@code attempt < 0} or {@code baseDelayMs <= 0}
     */
    public static Duration delay(int attempt, long baseDelayMs) {
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must be >= 0, got: " + attempt);
        }
        if (baseDelayMs <= 0) {
            throw new IllegalArgumentException("baseDelayMs must be > 0, got: " + baseDelayMs);
        }

        if (attempt >= Long.SIZE - 1) {
            return Duration.ofMillis(MAX_DELAY_MS);
        }
        long delayMs = baseDelayMs * (1L << attempt);
        if (delayMs > MAX_DELAY_MS || delayMs <= 0) {
            delayMs = MAX_DELAY_MS;
        }

        return Duration.ofMillis(delayMs);
    }
}
