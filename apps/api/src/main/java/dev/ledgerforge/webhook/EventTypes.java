package dev.ledgerforge.webhook;

import java.util.List;

/** Event types that merchants can subscribe to. */
public final class EventTypes {

    public static final String PAYMENT_CREATED = "payment.created";
    public static final String PAYMENT_SUCCEEDED = "payment.succeeded";
    public static final String PAYMENT_FAILED = "payment.failed";
    public static final String PAYMENT_CANCELLED = "payment.cancelled";
    public static final String PAYMENT_REFUNDED = "payment.refunded";
    public static final String PAYOUT_PAID = "payout.paid";
    public static final String TRANSFER_COMPLETED = "transfer.completed";
    public static final String WEBHOOK_TEST = "webhook.test";

    /** Events routable to merchant webhook endpoints. */
    public static final List<String> SUBSCRIBABLE = List.of(
            PAYMENT_CREATED, PAYMENT_SUCCEEDED, PAYMENT_FAILED, PAYMENT_CANCELLED, PAYMENT_REFUNDED, PAYOUT_PAID);

    private EventTypes() {}
}
