package dev.ledgerforge.payment;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Payment-intent lifecycle.
 *
 * <pre>
 *  CREATED ──confirm──▶ PROCESSING ──approved──▶ SUCCEEDED ──refund──▶ PARTIALLY_REFUNDED ──▶ REFUNDED
 *     │                     │                        └───────────full refund──────────────────▲
 *     └──cancel──▶ CANCELLED └──declined──▶ FAILED
 * </pre>
 *
 * PROCESSING is entered and left within the confirm transaction for the synchronous sandbox
 * adapter; it exists so an asynchronous adapter could park intents there without changing the
 * state machine.
 */
public enum PaymentStatus {
    CREATED,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    PARTIALLY_REFUNDED,
    REFUNDED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> TRANSITIONS = Map.of(
            CREATED, EnumSet.of(PROCESSING, CANCELLED),
            PROCESSING, EnumSet.of(SUCCEEDED, FAILED),
            SUCCEEDED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED),
            PARTIALLY_REFUNDED, EnumSet.of(PARTIALLY_REFUNDED, REFUNDED),
            FAILED, EnumSet.noneOf(PaymentStatus.class),
            CANCELLED, EnumSet.noneOf(PaymentStatus.class),
            REFUNDED, EnumSet.noneOf(PaymentStatus.class));

    public boolean canTransitionTo(PaymentStatus next) {
        return TRANSITIONS.get(this).contains(next);
    }

    public boolean isCaptured() {
        return this == SUCCEEDED || this == PARTIALLY_REFUNDED || this == REFUNDED;
    }

    public boolean isTerminal() {
        return TRANSITIONS.get(this).isEmpty();
    }
}
