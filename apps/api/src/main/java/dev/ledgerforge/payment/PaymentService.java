package dev.ledgerforge.payment;

import dev.ledgerforge.audit.AuditLog;
import dev.ledgerforge.audit.AuditService;
import dev.ledgerforge.common.config.AppProperties;
import dev.ledgerforge.common.error.ApiException;
import dev.ledgerforge.common.error.ErrorCode;
import dev.ledgerforge.common.money.Currency;
import dev.ledgerforge.common.security.AuthenticatedUser;
import dev.ledgerforge.common.util.ReferenceGenerator;
import dev.ledgerforge.common.web.PageParams;
import dev.ledgerforge.common.web.PageResponse;
import dev.ledgerforge.idempotency.IdempotencyService;
import dev.ledgerforge.ledger.AccountPurpose;
import dev.ledgerforge.ledger.LedgerAccount;
import dev.ledgerforge.ledger.LedgerAccountService;
import dev.ledgerforge.ledger.LedgerPostingRequest;
import dev.ledgerforge.ledger.LedgerService;
import dev.ledgerforge.ledger.LedgerTransactionType;
import dev.ledgerforge.merchant.MerchantProfile;
import dev.ledgerforge.merchant.MerchantProfileRepository;
import dev.ledgerforge.payment.PaymentDtos.CreatePaymentIntentRequest;
import dev.ledgerforge.payment.PaymentDtos.PaymentIntentView;
import dev.ledgerforge.user.UserDirectory;
import dev.ledgerforge.webhook.EventTypes;
import dev.ledgerforge.webhook.OutboxService;
import jakarta.persistence.criteria.Predicate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Payment-intent lifecycle. Capture posts, atomically with the status change and outbox event:
 *
 * <pre>
 *   Debit  Customer Wallet      amount
 *   Credit Merchant Payable     amount - fee
 *   Credit Platform Fee Revenue fee
 * </pre>
 */
@Service
public class PaymentService {

    private final PaymentIntentRepository intents;
    private final MerchantProfileRepository merchants;
    private final LedgerAccountService accounts;
    private final LedgerService ledger;
    private final FeeCalculator fees;
    private final PaymentAdapter adapter;
    private final IdempotencyService idempotency;
    private final OutboxService outbox;
    private final AuditService audit;
    private final UserDirectory directory;
    private final ObjectMapper objectMapper;
    private final AppProperties props;
    private final Clock clock;

    public PaymentService(
            PaymentIntentRepository intents,
            MerchantProfileRepository merchants,
            LedgerAccountService accounts,
            LedgerService ledger,
            FeeCalculator fees,
            PaymentAdapter adapter,
            IdempotencyService idempotency,
            OutboxService outbox,
            AuditService audit,
            UserDirectory directory,
            ObjectMapper objectMapper,
            AppProperties props,
            Clock clock) {
        this.intents = intents;
        this.merchants = merchants;
        this.accounts = accounts;
        this.ledger = ledger;
        this.fees = fees;
        this.adapter = adapter;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.audit = audit;
        this.directory = directory;
        this.objectMapper = objectMapper;
        this.props = props;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ create

    @Transactional
    public IdempotencyService.Result<PaymentIntentView> create(
            AuthenticatedUser customer, String key, CreatePaymentIntentRequest request) {
        return idempotency.execute(
                new IdempotencyService.Scope(customer.id(), "POST", "/api/v1/payment-intents", key),
                request,
                PaymentIntentView.class,
                201,
                () -> doCreate(customer, request),
                null);
    }

    private PaymentIntentView doCreate(AuthenticatedUser customer, CreatePaymentIntentRequest request) {
        Currency currency = Currency.parse(request.currency());
        if (request.amountMinor() > props.payments().maxAmountMinor()) {
            throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "Amount exceeds the maximum payment size");
        }
        MerchantProfile merchant = merchants
                .findByUserId(request.merchantId())
                .filter(MerchantProfile::isActive)
                .orElseThrow(() -> ApiException.notFound("Merchant"));
        LedgerAccount payable = accounts.findUserAccount(
                        merchant.getUserId(), AccountPurpose.MERCHANT_PAYABLE, currency)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.CURRENCY_MISMATCH, merchant.getBusinessName() + " does not accept " + currency));
        accounts.findUserAccount(customer.id(), AccountPurpose.CUSTOMER_WALLET, currency)
                .orElseThrow(() ->
                        new ApiException(ErrorCode.CURRENCY_MISMATCH, "You do not have a " + currency + " wallet"));

        int bps = fees.currentFeeBps();
        long fee = fees.platformFee(request.amountMinor(), bps);
        Instant now = clock.instant();
        Map<String, String> metadata = request.metadata() == null ? Map.of() : request.metadata();
        PaymentIntent intent = intents.save(new PaymentIntent(
                UUID.randomUUID(),
                ReferenceGenerator.next("PAY"),
                customer.id(),
                payable.getOwnerId(),
                request.amountMinor(),
                currency,
                bps,
                fee,
                request.description() == null ? null : request.description().trim(),
                objectMapper.writeValueAsString(metadata),
                now));
        outbox.record(EventTypes.PAYMENT_CREATED, "PAYMENT", intent.getId(), intent.getMerchantId(), eventData(intent));
        audit.record(
                customer,
                "PAYMENT_INTENT_CREATED",
                "PAYMENT",
                intent.getId(),
                AuditLog.Result.SUCCESS,
                Map.of("amountMinor", intent.getAmountMinor(), "currency", currency.name()));
        return toView(intent);
    }

    // ------------------------------------------------------------------ confirm

    @Transactional
    public IdempotencyService.Result<PaymentIntentView> confirm(
            AuthenticatedUser customer, UUID paymentId, String key) {
        return idempotency.execute(
                new IdempotencyService.Scope(
                        customer.id(), "POST", "/api/v1/payment-intents/" + paymentId + "/confirm", key),
                Map.of("paymentId", paymentId.toString()),
                PaymentIntentView.class,
                200,
                () -> doConfirm(customer, paymentId, key),
                PaymentIntentView::ledgerTransactionId);
    }

    private PaymentIntentView doConfirm(AuthenticatedUser customer, UUID paymentId, String key) {
        // Row lock: concurrent confirmations of the same intent are serialised; the second one
        // sees SUCCEEDED and is rejected by the state machine, so a payment is captured at most once.
        PaymentIntent intent = intents.lockById(paymentId)
                .filter(p -> p.getCustomerId().equals(customer.id()))
                .orElseThrow(() -> ApiException.notFound("Payment"));
        if (intent.getStatus() != PaymentStatus.CREATED) {
            throw ApiException.invalidState("Payment " + intent.getPublicReference() + " is already "
                    + intent.getStatus().name().toLowerCase());
        }
        Instant now = clock.instant();
        intent.transitionTo(PaymentStatus.PROCESSING, now);

        PaymentAdapter.Decision decision = adapter.authorize(intent);
        if (decision instanceof PaymentAdapter.Declined declined) {
            intent.markFailed(declined.code(), declined.message(), now);
            outbox.record(
                    EventTypes.PAYMENT_FAILED, "PAYMENT", intent.getId(), intent.getMerchantId(), eventData(intent));
            audit.record(
                    customer,
                    "PAYMENT_FAILED",
                    "PAYMENT",
                    intent.getId(),
                    AuditLog.Result.FAILURE,
                    Map.of("failureCode", declined.code()));
            return toView(intent);
        }

        Currency currency = intent.getCurrency();
        LedgerAccount wallet = accounts.findUserAccount(customer.id(), AccountPurpose.CUSTOMER_WALLET, currency)
                .orElseThrow(() -> new ApiException(ErrorCode.CURRENCY_MISMATCH, "No " + currency + " wallet"));
        LedgerAccount payable = accounts.findUserAccount(
                        intent.getMerchantId(), AccountPurpose.MERCHANT_PAYABLE, currency)
                .orElseThrow(() -> ApiException.notFound("Merchant account"));
        LedgerPostingRequest.Builder posting = LedgerPostingRequest.builder(LedgerTransactionType.PAYMENT, currency)
                .description(
                        intent.getDescription() == null
                                ? "Payment " + intent.getPublicReference()
                                : intent.getDescription())
                .debit(wallet.getId(), intent.getAmountMinor())
                .credit(payable.getId(), intent.getMerchantNetMinor())
                .relatedEntity(intent.getId())
                .idempotencyKey(key)
                .initiatedBy(customer.id());
        if (intent.getPlatformFeeMinor() > 0) {
            posting.credit(
                    accounts.systemAccount(AccountPurpose.FEE_REVENUE, currency).getId(), intent.getPlatformFeeMinor());
        }
        // Throws INSUFFICIENT_FUNDS before anything commits; the intent then remains CREATED.
        LedgerService.PostedTransaction posted = ledger.post(posting.build());

        intent.markSucceeded(posted.id(), now);
        outbox.record(
                EventTypes.PAYMENT_SUCCEEDED, "PAYMENT", intent.getId(), intent.getMerchantId(), eventData(intent));
        audit.record(
                customer,
                "PAYMENT_CAPTURED",
                "PAYMENT",
                intent.getId(),
                AuditLog.Result.SUCCESS,
                Map.of("ledgerReference", posted.reference()));
        return toView(intent);
    }

    // ------------------------------------------------------------------ cancel

    @Transactional
    public PaymentIntentView cancel(AuthenticatedUser customer, UUID paymentId) {
        PaymentIntent intent = intents.lockById(paymentId)
                .filter(p -> p.getCustomerId().equals(customer.id()))
                .orElseThrow(() -> ApiException.notFound("Payment"));
        if (intent.getStatus() == PaymentStatus.CANCELLED) {
            return toView(intent); // cancelling twice is a no-op
        }
        intent.markCancelled(clock.instant());
        outbox.record(
                EventTypes.PAYMENT_CANCELLED, "PAYMENT", intent.getId(), intent.getMerchantId(), eventData(intent));
        audit.record(customer, "PAYMENT_CANCELLED", "PAYMENT", intent.getId(), AuditLog.Result.SUCCESS, Map.of());
        return toView(intent);
    }

    // ------------------------------------------------------------------ queries

    public record PaymentFilter(String status, String currency, String q, Instant from, Instant to, String sort) {}

    @Transactional(readOnly = true)
    public PageResponse<PaymentIntentView> list(AuthenticatedUser user, PaymentFilter filter, PageParams page) {
        Specification<PaymentIntent> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            // Ownership is part of the query itself; there is no code path that lists all payments
            // except for administrators.
            switch (user.role()) {
                case CUSTOMER -> predicates.add(cb.equal(root.get("customerId"), user.id()));
                case MERCHANT -> predicates.add(cb.equal(root.get("merchantId"), user.id()));
                case ADMIN -> {}
            }
            if (filter.status() != null && !filter.status().isBlank()) {
                predicates.add(cb.equal(root.get("status"), parseStatus(filter.status())));
            }
            if (filter.currency() != null && !filter.currency().isBlank()) {
                predicates.add(cb.equal(root.get("currency"), Currency.parse(filter.currency())));
            }
            if (filter.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), filter.to()));
            }
            if (filter.q() != null && !filter.q().isBlank()) {
                String like = "%" + filter.q().trim().toLowerCase() + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("publicReference")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("description"), "")), like)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        Sort sort = switch (filter.sort() == null ? "createdAt,desc" : filter.sort()) {
            case "createdAt,asc" -> Sort.by(Sort.Direction.ASC, "createdAt");
            case "amount,desc" -> Sort.by(Sort.Direction.DESC, "amountMinor");
            case "amount,asc" -> Sort.by(Sort.Direction.ASC, "amountMinor");
            default -> Sort.by(Sort.Direction.DESC, "createdAt");
        };
        Page<PaymentIntent> result = intents.findAll(spec, PageRequest.of(page.page(), page.size(), sort));
        Map<UUID, UserDirectory.Party> parties = directory.lookup(result.getContent().stream()
                .flatMap(p -> java.util.stream.Stream.of(p.getCustomerId(), p.getMerchantId()))
                .toList());
        return PageResponse.from(result, p -> toView(p, parties));
    }

    @Transactional(readOnly = true)
    public PaymentIntentView get(AuthenticatedUser user, UUID paymentId) {
        return toView(findVisible(user, paymentId));
    }

    /** Returns the intent if the user is its customer, its merchant, or an admin; otherwise 404. */
    @Transactional(readOnly = true)
    public PaymentIntent findVisible(AuthenticatedUser user, UUID paymentId) {
        return intents.findById(paymentId)
                .filter(p -> user.isAdmin()
                        || p.getCustomerId().equals(user.id())
                        || p.getMerchantId().equals(user.id()))
                .orElseThrow(() -> ApiException.notFound("Payment"));
    }

    private static PaymentStatus parseStatus(String status) {
        try {
            return PaymentStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown payment status " + status);
        }
    }

    // ------------------------------------------------------------------ mapping

    public PaymentIntentView toView(PaymentIntent p) {
        return toView(p, directory.lookup(List.of(p.getCustomerId(), p.getMerchantId())));
    }

    private PaymentIntentView toView(PaymentIntent p, Map<UUID, UserDirectory.Party> parties) {
        UserDirectory.Party merchant = parties.get(p.getMerchantId());
        UserDirectory.Party customer = parties.get(p.getCustomerId());
        return new PaymentIntentView(
                p.getId(),
                p.getPublicReference(),
                p.getStatus().name(),
                p.getAmountMinor(),
                p.getCurrency().name(),
                p.getFeeBps(),
                p.getPlatformFeeMinor(),
                p.getMerchantNetMinor(),
                p.getRefundedAmountMinor(),
                p.refundableAmountMinor(),
                p.getDescription(),
                objectMapper.readValue(p.getMetadata(), new TypeReference<Map<String, String>>() {}),
                merchant == null ? null : new PaymentDtos.Party(merchant.id(), merchant.displayName(), null),
                customer == null
                        ? null
                        : new PaymentDtos.Party(
                                customer.id(), UserDirectory.shortName(customer.displayName()), customer.maskedEmail()),
                p.getFailureCode(),
                p.getFailureMessage(),
                p.getLedgerTransactionId(),
                p.getCreatedAt(),
                p.getUpdatedAt(),
                p.getCapturedAt(),
                p.getCancelledAt());
    }

    /** Webhook payload: amounts and references only, no customer personal data. */
    public static Map<String, Object> eventData(PaymentIntent p) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("paymentId", p.getId().toString());
        data.put("reference", p.getPublicReference());
        data.put("status", p.getStatus().name());
        data.put("amountMinor", p.getAmountMinor());
        data.put("currency", p.getCurrency().name());
        data.put("platformFeeMinor", p.getPlatformFeeMinor());
        data.put("merchantNetMinor", p.getMerchantNetMinor());
        data.put("refundedAmountMinor", p.getRefundedAmountMinor());
        if (p.getFailureCode() != null) {
            data.put("failureCode", p.getFailureCode());
        }
        if (p.getDescription() != null) {
            data.put("description", p.getDescription());
        }
        return data;
    }
}
