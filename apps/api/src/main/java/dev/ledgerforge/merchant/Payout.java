package dev.ledgerforge.merchant;

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

/** Simulated settlement of merchant funds to an external bank account. */
@Entity
@Immutable
@Table(name = "payouts")
public class Payout {

    @Id
    private UUID id;

    @Column(name = "public_reference", nullable = false, length = 32)
    private String publicReference;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "ledger_transaction_id", nullable = false)
    private UUID ledgerTransactionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Payout() {}

    Payout(
            UUID id,
            String publicReference,
            UUID merchantId,
            long amountMinor,
            Currency currency,
            UUID ledgerTransactionId,
            Instant now) {
        this.id = id;
        this.publicReference = publicReference;
        this.merchantId = merchantId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = "PAID";
        this.ledgerTransactionId = ledgerTransactionId;
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getPublicReference() {
        return publicReference;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public Currency getCurrency() {
        return currency;
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
