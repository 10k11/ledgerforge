package dev.ledgerforge.payment;

/**
 * Boundary to a payment processor. LedgerForge only ships a deterministic sandbox implementation;
 * it never receives card numbers, CVVs or bank credentials.
 */
public interface PaymentAdapter {

    Decision authorize(PaymentIntent intent);

    sealed interface Decision permits Approved, Declined {}

    record Approved() implements Decision {}

    record Declined(String code, String message) implements Decision {}
}
