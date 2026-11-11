package dev.ledgerforge.ledger;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Balance projection for one ledger account, expressed in the account's normal-balance direction.
 * It exists so that balance checks are O(1) under a row lock; it is updated only by {@link
 * LedgerService} inside the same database transaction that inserts the entries, and is verified
 * against the entries by {@link ReconciliationService}.
 */
@Entity
@Table(name = "account_balances")
public class AccountBalance {

    @Id
    private UUID id;

    @Column(name = "ledger_account_id", nullable = false)
    private UUID ledgerAccountId;

    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    @Column(name = "allow_negative", nullable = false)
    private boolean allowNegative;

    @Version
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AccountBalance() {}

    AccountBalance(UUID id, UUID ledgerAccountId, boolean allowNegative, Instant now) {
        this.id = id;
        this.ledgerAccountId = ledgerAccountId;
        this.balanceMinor = 0;
        this.allowNegative = allowNegative;
        this.updatedAt = now;
    }

    /** Applies a signed delta. Overflow is impossible to ignore: Math.addExact throws. */
    void apply(long signedDelta, Instant now) {
        this.balanceMinor = Math.addExact(balanceMinor, signedDelta);
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getLedgerAccountId() {
        return ledgerAccountId;
    }

    public long getBalanceMinor() {
        return balanceMinor;
    }

    public boolean isAllowNegative() {
        return allowNegative;
    }

    public long getVersion() {
        return version;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
