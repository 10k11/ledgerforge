package dev.ledgerforge.ledger;

public enum EntryType {
    DEBIT,
    CREDIT;

    public EntryType opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
