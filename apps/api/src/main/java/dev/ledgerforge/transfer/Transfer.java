package dev.ledgerforge.transfer;

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

/** A completed wallet-to-wallet transfer. Immutable; the money movement itself is in the ledger. */
@Entity
@Immutable
@Table(name = "transfers")
public class Transfer {

    @Id
    private UUID id;

    @Column(name = "public_reference", nullable = false, length = 32)
    private String publicReference;

    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    @Column(name = "recipient_id", nullable = false)
    private UUID recipientId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Column(length = 140)
    private String description;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "ledger_transaction_id", nullable = false)
    private UUID ledgerTransactionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Transfer() {}

    Transfer(
            UUID id,
            String publicReference,
            UUID senderId,
            UUID recipientId,
            long amountMinor,
            Currency currency,
            String description,
            UUID ledgerTransactionId,
            Instant now) {
        this.id = id;
        this.publicReference = publicReference;
        this.senderId = senderId;
        this.recipientId = recipientId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.description = description;
        this.status = "COMPLETED";
        this.ledgerTransactionId = ledgerTransactionId;
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getPublicReference() {
        return publicReference;
    }

    public UUID getSenderId() {
        return senderId;
    }

    public UUID getRecipientId() {
        return recipientId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public Currency getCurrency() {
        return currency;
    }

    public String getDescription() {
        return description;
    }

    public String getStatus() {
        return status;
    }

    public UUID getLedgerTransactionId() {
        return ledgerTransactionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
