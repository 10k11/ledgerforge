package dev.ledgerforge.ledger;

/**
 * Account classes of the accounting equation {@code Assets = Liabilities + Equity + (Revenue -
 * Expenses)}. Each class has a normal balance: the side on which increases are recorded.
 *
 * <pre>
 *   Type       Normal   Debit effect   Credit effect
 *   ASSET      DEBIT    increase       decrease
 *   EXPENSE    DEBIT    increase       decrease
 *   LIABILITY  CREDIT   decrease       increase
 *   EQUITY     CREDIT   decrease       increase
 *   REVENUE    CREDIT   decrease       increase
 * </pre>
 */
public enum AccountType {
    ASSET(EntryType.DEBIT),
    LIABILITY(EntryType.CREDIT),
    EQUITY(EntryType.CREDIT),
    REVENUE(EntryType.CREDIT),
    EXPENSE(EntryType.DEBIT);

    private final EntryType normalBalance;

    AccountType(EntryType normalBalance) {
        this.normalBalance = normalBalance;
    }

    public EntryType normalBalance() {
        return normalBalance;
    }

    /** Signed effect of a posting on a balance expressed in this type's normal direction. */
    public long signedDelta(EntryType entryType, long amountMinor) {
        return entryType == normalBalance ? amountMinor : -amountMinor;
    }
}
