package dev.ledgerforge.ledger;

/** Business role of a ledger account within the platform's chart of accounts. */
public enum AccountPurpose {
    /** Money the platform owes a customer (LIABILITY, credit-normal). */
    CUSTOMER_WALLET(AccountType.LIABILITY, "Customer Wallet"),
    /** Money the platform owes a merchant (LIABILITY, credit-normal). */
    MERCHANT_PAYABLE(AccountType.LIABILITY, "Merchant Payable"),
    /** Platform's simulated settlement-bank position (ASSET, debit-normal). */
    FUNDING_CLEARING(AccountType.ASSET, "Platform Funding Clearing"),
    /** Platform fees earned (REVENUE, credit-normal). */
    FEE_REVENUE(AccountType.REVENUE, "Platform Fee Revenue");

    private final AccountType accountType;
    private final String label;

    AccountPurpose(AccountType accountType, String label) {
        this.accountType = accountType;
        this.label = label;
    }

    public AccountType accountType() {
        return accountType;
    }

    public String label() {
        return label;
    }

    public boolean isUserOwned() {
        return this == CUSTOMER_WALLET || this == MERCHANT_PAYABLE;
    }
}
