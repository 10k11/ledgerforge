package dev.ledgerforge.refund;

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

@Entity
@Immutable
@Table(name = "refunds")
public class Refund {

    @Id
    private UUID id;

    @Column(name = "public_reference", nullable = false, length = 32)
    private String publicReference;

    @Column(name = "payment_intent_id", nullable = false)
    private UUID paymentIntentId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(name = "fee_reversal_minor", nullable = false)
    private long feeReversalMinor;

    @Column(name = "merchant_debit_minor", nullable = false)
    private long merchantDebitMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 255)
    private String reason;

    @Column(name = "requested_by", nullable = false)
    private UUID requestedBy;

    @Column(name = "ledger_transaction_id", nullable = false)
    private UUID ledgerTransactionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Refund() {}

    Refund(
            UUID id,
            String publicReference,
            UUID paymentIntentId,
            long amountMinor,
            long feeReversalMinor,
            long merchantDebitMinor,
            Currency currency,
            String reason,
            UUID requestedBy,
            UUID ledgerTransactionId,
            Instant now) {
        this.id = id;
        this.publicReference = publicReference;
        this.paymentIntentId = paymentIntentId;
        this.amountMinor = amountMinor;
        this.feeReversalMinor = feeReversalMinor;
        this.merchantDebitMinor = merchantDebitMinor;
        this.currency = currency;
        this.status = "SUCCEEDED";
        this.reason = reason;
        this.requestedBy = requestedBy;
        this.ledgerTransactionId = ledgerTransactionId;
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getPublicReference() {
        return publicReference;
    }

    public UUID getPaymentIntentId() {
        return paymentIntentId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public long getFeeReversalMinor() {
        return feeReversalMinor;
    }

    public long getMerchantDebitMinor() {
        return merchantDebitMinor;
    }

    public Currency getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public UUID getLedgerTransactionId() {
        return ledgerTransactionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
