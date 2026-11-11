package dev.ledgerforge.payment;

import dev.ledgerforge.common.error.ApiException;
import dev.ledgerforge.common.money.Currency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;

@Entity
@Table(name = "payment_intents")
public class PaymentIntent {

    @Id
    private UUID id;

    @Column(name = "public_reference", nullable = false, length = 32)
    private String publicReference;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Column(name = "fee_bps", nullable = false)
    private int feeBps;

    @Column(name = "platform_fee_minor", nullable = false)
    private long platformFeeMinor;

    @Column(name = "merchant_net_minor", nullable = false)
    private long merchantNetMinor;

    @Column(name = "refunded_amount_minor", nullable = false)
    private long refundedAmountMinor;

    @Column(name = "refunded_fee_minor", nullable = false)
    private long refundedFeeMinor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private PaymentStatus status;

    @Column(length = 255)
    private String description;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String metadata;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_message", length = 255)
    private String failureMessage;

    @Column(name = "ledger_transaction_id")
    private UUID ledgerTransactionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "captured_at")
    private Instant capturedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Version
    private long version;

    protected PaymentIntent() {}

    public PaymentIntent(
            UUID id,
            String publicReference,
            UUID customerId,
            UUID merchantId,
            long amountMinor,
            Currency currency,
            int feeBps,
            long platformFeeMinor,
            String description,
            String metadataJson,
            Instant now) {
        this.id = id;
        this.publicReference = publicReference;
        this.customerId = customerId;
        this.merchantId = merchantId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.feeBps = feeBps;
        this.platformFeeMinor = platformFeeMinor;
        this.merchantNetMinor = Math.subtractExact(amountMinor, platformFeeMinor);
        this.description = description;
        this.metadata = metadataJson;
        this.status = PaymentStatus.CREATED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Single choke point for state changes: illegal transitions are impossible to persist. */
    void transitionTo(PaymentStatus next, Instant now) {
        if (!status.canTransitionTo(next)) {
            throw ApiException.invalidState(
                    "Payment " + publicReference + " cannot move from " + status + " to " + next);
        }
        this.status = next;
        this.updatedAt = now;
    }

    void markSucceeded(UUID ledgerTransactionId, Instant now) {
        transitionTo(PaymentStatus.SUCCEEDED, now);
        this.ledgerTransactionId = ledgerTransactionId;
        this.capturedAt = now;
    }

    void markFailed(String code, String message, Instant now) {
        transitionTo(PaymentStatus.FAILED, now);
        this.failureCode = code;
        this.failureMessage = message;
    }

    void markCancelled(Instant now) {
        transitionTo(PaymentStatus.CANCELLED, now);
        this.cancelledAt = now;
    }

    /** Records a refund and moves to PARTIALLY_REFUNDED or REFUNDED. */
    public void applyRefund(long amountMinor, long feeReversalMinor, Instant now) {
        long refunded = Math.addExact(refundedAmountMinor, amountMinor);
        long refundedFee = Math.addExact(refundedFeeMinor, feeReversalMinor);
        if (refunded > this.amountMinor || refundedFee > platformFeeMinor) {
            throw new IllegalStateException("Refund totals exceed the captured payment");
        }
        transitionTo(refunded == this.amountMinor ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED, now);
        this.refundedAmountMinor = refunded;
        this.refundedFeeMinor = refundedFee;
    }

    public long refundableAmountMinor() {
        return status.isCaptured() ? amountMinor - refundedAmountMinor : 0;
    }

    public UUID getId() {
        return id;
    }

    public String getPublicReference() {
        return publicReference;
    }

    public UUID getCustomerId() {
        return customerId;
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

    public int getFeeBps() {
        return feeBps;
    }

    public long getPlatformFeeMinor() {
        return platformFeeMinor;
    }

    public long getMerchantNetMinor() {
        return merchantNetMinor;
    }

    public long getRefundedAmountMinor() {
        return refundedAmountMinor;
    }

    public long getRefundedFeeMinor() {
        return refundedFeeMinor;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getDescription() {
        return description;
    }

    public String getMetadata() {
        return metadata;
    }

    public String getFailureCode() {
        return failureCode;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public UUID getLedgerTransactionId() {
        return ledgerTransactionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }
}
