package dev.ledgerforge.common.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.function.Supplier;

/**
 * The application clock (UTC). It delegates to the system clock, but the development data seeder
 * can run code "as of" a past instant on the current thread, so demo history is created through
 * the real services (ledger, idempotency, outbox) with realistic timestamps.
 *
 * <p>Nothing outside {@code dev.ledgerforge.seed} calls {@link #runAt}; the offset is thread-local
 * and always reset, so request threads and background workers always see real time.
 */
public final class ShiftableClock extends Clock {

    private static final ThreadLocal<Duration> OFFSET = ThreadLocal.withInitial(() -> Duration.ZERO);

    private final Clock base;

    public ShiftableClock(Clock base) {
        this.base = base;
    }

    @Override
    public ZoneId getZone() {
        return base.getZone();
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new ShiftableClock(base.withZone(zone));
    }

    @Override
    public Instant instant() {
        return base.instant().plus(OFFSET.get());
    }

    /** Runs {@code action} on the current thread with the clock reading {@code at}. */
    public <T> T runAt(Instant at, Supplier<T> action) {
        Duration previous = OFFSET.get();
        OFFSET.set(Duration.between(base.instant(), at));
        try {
            return action.get();
        } finally {
            OFFSET.set(previous);
        }
    }
}
