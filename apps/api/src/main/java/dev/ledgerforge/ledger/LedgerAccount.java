package dev.ledgerforge.ledger;

import dev.ledgerforge.common.money.Currency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ledger_accounts")
public class LedgerAccount {

    public enum Status {
        ACTIVE,
        FROZEN,
        CLOSED
    }

    @Id
    private UUID id;

    @Column(name = "account_number", nullable = false, length = 32)
    private String accountNumber;

    @Column(name = "owner_id")
    private UUID ownerId;

    @Column(name = "account_name", nullable = false, length = 120)
    private String accountName;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 16)
    private AccountType accountType;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_purpose", nullable = false, length = 32)
    private AccountPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "normal_balance", nullable = false, length = 6)
    private EntryType normalBalance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Column(name = "allow_negative", nullable = false)
    private boolean allowNegative;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerAccount() {}

    public LedgerAccount(
            UUID id,
            String accountNumber,
            UUID ownerId,
            String accountName,
            AccountPurpose purpose,
            Currency currency,
            boolean allowNegative,
            Instant now) {
        this.id = id;
        this.accountNumber = accountNumber;
        this.ownerId = ownerId;
        this.accountName = accountName;
        this.purpose = purpose;
        this.accountType = purpose.accountType();
        this.normalBalance = purpose.accountType().normalBalance();
        this.currency = currency;
        this.allowNegative = allowNegative;
        this.status = Status.ACTIVE;
        this.createdAt = now;
    }

    public boolean isActive() {
        return status == Status.ACTIVE;
    }

    public UUID getId() {
        return id;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getAccountName() {
        return accountName;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public AccountPurpose getPurpose() {
        return purpose;
    }

    public EntryType getNormalBalance() {
        return normalBalance;
    }

    public Currency getCurrency() {
        return currency;
    }

    public boolean isAllowNegative() {
        return allowNegative;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
