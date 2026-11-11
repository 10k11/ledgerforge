package dev.ledgerforge.refund;

import dev.ledgerforge.audit.AuditLog;
import dev.ledgerforge.audit.AuditService;
import dev.ledgerforge.common.error.ApiException;
import dev.ledgerforge.common.error.ErrorCode;
import dev.ledgerforge.common.money.Currency;
import dev.ledgerforge.common.security.AuthenticatedUser;
import dev.ledgerforge.common.util.ReferenceGenerator;
import dev.ledgerforge.idempotency.IdempotencyService;
import dev.ledgerforge.ledger.AccountPurpose;
import dev.ledgerforge.ledger.LedgerAccount;
import dev.ledgerforge.ledger.LedgerAccountService;
import dev.ledgerforge.ledger.LedgerPostingRequest;
import dev.ledgerforge.ledger.LedgerService;
import dev.ledgerforge.ledger.LedgerTransactionType;
import dev.ledgerforge.payment.FeeCalculator;
import dev.ledgerforge.payment.PaymentIntent;
import dev.ledgerforge.payment.PaymentIntentRepository;
import dev.ledgerforge.payment.PaymentService;
import dev.ledgerforge.webhook.EventTypes;
import dev.ledgerforge.webhook.OutboxService;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Full and partial refunds. A refund is a ledger REFUND transaction that references the original
 * payment's ledger transaction and posts the mirror image of (part of) it:
 *
 * <pre>
 *   Debit  Merchant Payable     merchantDebit
 *   Debit  Platform Fee Revenue feeReversal
 *   Credit Customer Wallet      amount
 * </pre>
 *
 * The ledger's reversal invariants guarantee cumulative refunds can never exceed the original
 * postings on any account, independently of the checks performed here.
 */
@Service
public class RefundService {

    private final PaymentIntentRepository intents;
    private final RefundRepository refunds;
    private final PaymentService payments;
    private final LedgerAccountService accounts;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final OutboxService outbox;
    private final AuditService audit;
    private final Clock clock;

    public RefundService(
            PaymentIntentRepository intents,
            RefundRepository refunds,
            PaymentService payments,
            LedgerAccountService accounts,
            LedgerService ledger,
            IdempotencyService idempotency,
            OutboxService outbox,
            AuditService audit,
            Clock clock) {
        this.intents = intents;
        this.refunds = refunds;
        this.payments = payments;
        this.accounts = accounts;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
    }

    public record CreateRefundRequest(
            @Positive @Schema(description = "Amount in minor units; omit to refund the full remaining amount")
            Long amountMinor,

            @Size(max = 255) @Schema(example = "Customer returned the item")
            String reason) {}

    public record RefundView(
            UUID id,
            String reference,
            UUID paymentId,
            String paymentReference,
            long amountMinor,
            long feeReversalMinor,
            long merchantDebitMinor,
            String currency,
            String status,
            String reason,
            UUID ledgerTransactionId,
            String paymentStatus,
            long paymentRefundedAmountMinor,
            long paymentRefundableAmountMinor,
            Instant createdAt) {}

    @Transactional
    public IdempotencyService.Result<RefundView> create(
            AuthenticatedUser actor, UUID paymentId, String key, CreateRefundRequest request) {
        return idempotency.execute(
                new IdempotencyService.Scope(actor.id(), "POST", "/api/v1/payments/" + paymentId + "/refunds", key),
                request,
                RefundView.class,
                201,
                () -> doCreate(actor, paymentId, key, request),
                RefundView::ledgerTransactionId);
    }

    private RefundView doCreate(AuthenticatedUser actor, UUID paymentId, String key, CreateRefundRequest request) {
        // Lock the payment first: concurrent refunds of one payment are serialised, so the
        // "remaining refundable" check below cannot be raced.
        PaymentIntent intent = intents.lockById(paymentId)
                .filter(p -> actor.isAdmin() || p.getMerchantId().equals(actor.id()))
                .orElseThrow(() -> ApiException.notFound("Payment"));
        if (!intent.getStatus().isCaptured()) {
            throw ApiException.invalidState(
                    "Only captured payments can be refunded (status is " + intent.getStatus() + ")");
        }
        long remaining = intent.refundableAmountMinor();
        if (remaining == 0) {
            throw ApiException.invalidState("Payment " + intent.getPublicReference() + " is already fully refunded");
        }
        long amount = request.amountMinor() == null ? remaining : request.amountMinor();
        if (amount > remaining) {
            throw new ApiException(
                    ErrorCode.REFUND_EXCEEDS_REMAINING,
                    "Refund exceeds the remaining refundable amount",
                    Map.of("refundableAmountMinor", remaining));
        }
        FeeCalculator.RefundSplit split = FeeCalculator.refundSplit(
                intent.getAmountMinor(),
                intent.getPlatformFeeMinor(),
                intent.getRefundedAmountMinor(),
                intent.getRefundedFeeMinor(),
                amount);

        Currency currency = intent.getCurrency();
        LedgerAccount payable = accounts.findUserAccount(
                        intent.getMerchantId(), AccountPurpose.MERCHANT_PAYABLE, currency)
                .orElseThrow(() -> ApiException.notFound("Merchant account"));
        LedgerAccount wallet = accounts.findUserAccount(
                        intent.getCustomerId(), AccountPurpose.CUSTOMER_WALLET, currency)
                .orElseThrow(() -> ApiException.notFound("Customer wallet"));

        UUID refundId = UUID.randomUUID();
        LedgerPostingRequest.Builder posting = LedgerPostingRequest.builder(LedgerTransactionType.REFUND, currency)
                .description("Refund of " + intent.getPublicReference())
                .credit(wallet.getId(), amount)
                .reverses(intent.getLedgerTransactionId())
                .relatedEntity(refundId)
                .idempotencyKey(key)
                .initiatedBy(actor.id());
        if (split.merchantDebitMinor() > 0) {
            posting.debit(payable.getId(), split.merchantDebitMinor());
        }
        if (split.feeReversalMinor() > 0) {
            posting.debit(
                    accounts.systemAccount(AccountPurpose.FEE_REVENUE, currency).getId(), split.feeReversalMinor());
        }
        // MERCHANT_INSUFFICIENT_BALANCE if the merchant has already paid the funds out.
        LedgerService.PostedTransaction posted = ledger.post(posting.build());

        Instant now = clock.instant();
        intent.applyRefund(amount, split.feeReversalMinor(), now);
        Refund refund = refunds.save(new Refund(
                refundId,
                ReferenceGenerator.next("RFD"),
                intent.getId(),
                amount,
                split.feeReversalMinor(),
                split.merchantDebitMinor(),
                currency,
                request.reason() == null ? null : request.reason().trim(),
                actor.id(),
                posted.id(),
                now));

        Map<String, Object> data = PaymentService.eventData(intent);
        data.put(
                "refund",
                Map.of(
                        "refundId", refund.getId().toString(),
                        "reference", refund.getPublicReference(),
                        "amountMinor", amount,
                        "feeReversalMinor", split.feeReversalMinor()));
        outbox.record(EventTypes.PAYMENT_REFUNDED, "PAYMENT", intent.getId(), intent.getMerchantId(), data);
        audit.record(
                actor,
                "PAYMENT_REFUNDED",
                "REFUND",
                refund.getId(),
                AuditLog.Result.SUCCESS,
                Map.of("paymentId", intent.getId().toString(), "amountMinor", amount));
        return view(refund, intent);
    }

    @Transactional(readOnly = true)
    public List<RefundView> list(AuthenticatedUser user, UUID paymentId) {
        PaymentIntent intent = payments.findVisible(user, paymentId);
        return refunds.findByPaymentIntentIdOrderByCreatedAtAsc(paymentId).stream()
                .map(r -> view(r, intent))
                .toList();
    }

    private static RefundView view(Refund r, PaymentIntent p) {
        return new RefundView(
                r.getId(),
                r.getPublicReference(),
                p.getId(),
                p.getPublicReference(),
                r.getAmountMinor(),
                r.getFeeReversalMinor(),
                r.getMerchantDebitMinor(),
                r.getCurrency().name(),
                r.getStatus(),
                r.getReason(),
                r.getLedgerTransactionId(),
                p.getStatus().name(),
                p.getRefundedAmountMinor(),
                p.refundableAmountMinor(),
                r.getCreatedAt());
    }
}
