package dev.ledgerforge.ledger;

import dev.ledgerforge.common.money.Currency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Immutable;

/** A single debit or credit posting. Immutable; amounts are always strictly positive. */
@Entity
@Immutable
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    private UUID id;

    @Column(name = "sequence_number", insertable = false, updatable = false)
    private Long sequenceNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ledger_transaction_id", nullable = false)
    private LedgerTransaction transaction;

    @Column(name = "ledger_account_id", nullable = false)
    private UUID ledgerAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 6)
    private EntryType entryType;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntry() {}

    LedgerEntry(
            UUID id,
            LedgerTransaction transaction,
            UUID ledgerAccountId,
            EntryType entryType,
            long amountMinor,
            Currency currency,
            Instant now) {
        this.id = id;
        this.transaction = transaction;
        this.ledgerAccountId = ledgerAccountId;
        this.entryType = entryType;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public Long getSequenceNumber() {
        return sequenceNumber;
    }

    public LedgerTransaction getTransaction() {
        return transaction;
    }

    public UUID getLedgerAccountId() {
        return ledgerAccountId;
    }

    public EntryType getEntryType() {
        return entryType;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public Currency getCurrency() {
        return currency;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
