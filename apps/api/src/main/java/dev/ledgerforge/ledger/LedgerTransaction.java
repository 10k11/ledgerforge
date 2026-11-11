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
import org.hibernate.annotations.Immutable;

/**
 * A posted, balanced set of ledger entries. Immutable once written: Hibernate never issues
 * updates for it ({@link Immutable}) and a database trigger rejects UPDATE/DELETE.
 */
@Entity
@Immutable
@Table(name = "ledger_transactions")
public class LedgerTransaction {

    public enum Status {
        POSTED
    }

    @Id
    private UUID id;

    @Column(name = "reference_number", nullable = false, length = 32)
    private String referenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 16)
    private LedgerTransactionType transactionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(nullable = false, length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "related_entity_id")
    private UUID relatedEntityId;

    @Column(name = "reverses_transaction_id")
    private UUID reversesTransactionId;

    @Column(name = "initiated_by")
    private UUID initiatedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    protected LedgerTransaction() {}

    LedgerTransaction(
            UUID id,
            String referenceNumber,
            LedgerTransactionType type,
            String description,
            Currency currency,
            String idempotencyKey,
            UUID relatedEntityId,
            UUID reversesTransactionId,
            UUID initiatedBy,
            Instant now) {
        this.id = id;
        this.referenceNumber = referenceNumber;
        this.transactionType = type;
        this.status = Status.POSTED;
        this.description = description;
        this.currency = currency;
        this.idempotencyKey = idempotencyKey;
        this.relatedEntityId = relatedEntityId;
        this.reversesTransactionId = reversesTransactionId;
        this.initiatedBy = initiatedBy;
        this.createdAt = now;
        this.postedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getReferenceNumber() {
        return referenceNumber;
    }

    public LedgerTransactionType getTransactionType() {
        return transactionType;
    }

    public Status getStatus() {
        return status;
    }

    public String getDescription() {
        return description;
    }

    public Currency getCurrency() {
        return currency;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getRelatedEntityId() {
        return relatedEntityId;
    }

    public UUID getReversesTransactionId() {
        return reversesTransactionId;
    }

    public UUID getInitiatedBy() {
        return initiatedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPostedAt() {
        return postedAt;
    }
}
