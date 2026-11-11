package dev.ledgerforge.ledger;

public enum LedgerTransactionType {
    TOP_UP,
    TRANSFER,
    PAYMENT,
    REFUND,
    PAYOUT,
    REVERSAL;

    public boolean isReversal() {
        return this == REFUND || this == REVERSAL;
    }
}
