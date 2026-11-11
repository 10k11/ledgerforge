package dev.ledgerforge.merchant;

import dev.ledgerforge.audit.AuditLog;
import dev.ledgerforge.audit.AuditService;
import dev.ledgerforge.common.config.AppProperties;
import dev.ledgerforge.common.error.ApiException;
import dev.ledgerforge.common.error.ErrorCode;
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
import dev.ledgerforge.merchant.MerchantDtos.DailyPoint;
import dev.ledgerforge.webhook.EventTypes;
import dev.ledgerforge.webhook.OutboxService;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Merchant-facing reporting and payouts. Every query is scoped to the authenticated merchant's own
 * user id; aggregates are computed in PostgreSQL from payment intents and refunds.
 */
@Service
public class MerchantService {

    private final MerchantProfileRepository profiles;
    private final PayoutRepository payouts;
    private final LedgerAccountService accounts;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final OutboxService outbox;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final AppProperties props;
    private final Clock clock;

    public MerchantService(
            MerchantProfileRepository profiles,
            PayoutRepository payouts,
            LedgerAccountService accounts,
            LedgerService ledger,
            IdempotencyService idempotency,
            OutboxService outbox,
            AuditService audit,
            JdbcTemplate jdbc,
            AppProperties props,
            Clock clock) {
        this.profiles = profiles;
        this.payouts = payouts;
        this.accounts = accounts;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.audit = audit;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<MerchantDtos.DirectoryEntry> directory(String q) {
        String like = q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%";
        return profiles.searchActive(like).stream()
                .map(m -> new MerchantDtos.DirectoryEntry(
                        m.getUserId(),
                        m.getBusinessName(),
                        m.getCategory(),
                        accounts.accountsOwnedBy(m.getUserId()).stream()
                                .filter(a -> a.getPurpose() == AccountPurpose.MERCHANT_PAYABLE)
                                .map(a -> a.getCurrency().name())
                                .toList()))
                .toList();
    }

    @Transactional(readOnly = true)
    public LedgerAccount payableOf(AuthenticatedUser merchant) {
        return accounts.accountsOwnedBy(merchant.id()).stream()
                .filter(a -> a.getPurpose() == AccountPurpose.MERCHANT_PAYABLE)
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Merchant account"));
    }

    @Transactional(readOnly = true)
    public MerchantProfile profileOf(AuthenticatedUser merchant) {
        return profiles.findByUserId(merchant.id()).orElseThrow(() -> ApiException.notFound("Merchant profile"));
    }

    @Transactional(readOnly = true)
    public MerchantDtos.ProfileView profile(AuthenticatedUser merchant) {
        return profileView(profileOf(merchant));
    }

    @Transactional
    public MerchantDtos.ProfileView updateProfile(AuthenticatedUser merchant, MerchantDtos.UpdateProfileRequest req) {
        MerchantProfile profile = profileOf(merchant);
        profile.update(
                req.businessName().trim(), req.category().trim(), req.supportEmail(), req.website(), clock.instant());
        audit.record(
                merchant, "MERCHANT_PROFILE_UPDATED", "MERCHANT", profile.getId(), AuditLog.Result.SUCCESS, Map.of());
        return profileView(profile);
    }

    private static MerchantDtos.ProfileView profileView(MerchantProfile p) {
        return new MerchantDtos.ProfileView(
                p.getId(),
                p.getBusinessName(),
                p.getCategory(),
                p.getSupportEmail(),
                p.getWebsite(),
                p.getStatus(),
                p.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public MerchantDtos.BalanceView balance(AuthenticatedUser merchant) {
        LedgerAccount payable = payableOf(merchant);
        return new MerchantDtos.BalanceView(
                payable.getId(),
                payable.getAccountNumber(),
                payable.getCurrency().name(),
                ledger.balanceOf(payable.getId()),
                clock.instant());
    }

    @Transactional(readOnly = true)
    public MerchantDtos.Dashboard dashboard(AuthenticatedUser merchant) {
        LedgerAccount payable = payableOf(merchant);
        MerchantProfile profile = profileOf(merchant);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        jdbc.query(
                "SELECT status, COUNT(*) FROM payment_intents WHERE merchant_id = ? GROUP BY status ORDER BY status",
                rs -> {
                    byStatus.put(rs.getString(1), rs.getLong(2));
                },
                merchant.id());
        long[] totals = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(amount_minor), 0), COALESCE(SUM(platform_fee_minor), 0),
                       COALESCE(SUM(merchant_net_minor), 0), COALESCE(SUM(refunded_amount_minor), 0)
                  FROM payment_intents
                 WHERE merchant_id = ? AND status IN ('SUCCEEDED', 'PARTIALLY_REFUNDED', 'REFUNDED')
                """, (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)}, merchant.id());
        Long paidOut = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_minor), 0) FROM payouts WHERE merchant_id = ?", Long.class, merchant.id());
        LocalDate today = LocalDate.now(clock);
        long successful = byStatus.getOrDefault("SUCCEEDED", 0L)
                + byStatus.getOrDefault("PARTIALLY_REFUNDED", 0L)
                + byStatus.getOrDefault("REFUNDED", 0L);
        return new MerchantDtos.Dashboard(
                profile.getBusinessName(),
                payable.getCurrency().name(),
                ledger.balanceOf(payable.getId()),
                totals[0],
                totals[1],
                totals[2],
                totals[3],
                paidOut == null ? 0 : paidOut,
                successful,
                byStatus.getOrDefault("FAILED", 0L),
                byStatus.getOrDefault("CREATED", 0L),
                byStatus,
                daily(merchant.id(), today.minusDays(29), today));
    }

    @Transactional(readOnly = true)
    public MerchantDtos.Report report(AuthenticatedUser merchant, LocalDate from, LocalDate to) {
        LocalDate end = to == null ? LocalDate.now(clock) : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end) || ChronoUnit.DAYS.between(start, end) > 366) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Report range must be 1-366 days");
        }
        List<DailyPoint> days = daily(merchant.id(), start, end);
        return new MerchantDtos.Report(
                start,
                end,
                payableOf(merchant).getCurrency().name(),
                days.stream().mapToLong(DailyPoint::grossMinor).sum(),
                days.stream().mapToLong(DailyPoint::feesMinor).sum(),
                days.stream().mapToLong(DailyPoint::netMinor).sum(),
                days.stream().mapToLong(DailyPoint::refundsMinor).sum(),
                days.stream().mapToLong(DailyPoint::count).sum(),
                days);
    }

    /** Daily series with zero-filled days (generate_series), captured volume by capture date. */
    private List<DailyPoint> daily(UUID merchantId, LocalDate from, LocalDate to) {
        return jdbc.query(
                """
                WITH days AS (
                    SELECT d::date AS day FROM generate_series(?::date, ?::date, interval '1 day') d),
                captured AS (
                    SELECT (captured_at AT TIME ZONE 'UTC')::date AS day,
                           SUM(amount_minor) gross, SUM(platform_fee_minor) fees, SUM(merchant_net_minor) net,
                           COUNT(*) cnt
                      FROM payment_intents
                     WHERE merchant_id = ? AND captured_at IS NOT NULL
                       AND captured_at >= ? AND captured_at < ?
                     GROUP BY 1),
                refunded AS (
                    SELECT (r.created_at AT TIME ZONE 'UTC')::date AS day, SUM(r.amount_minor) refunds
                      FROM refunds r JOIN payment_intents p ON p.id = r.payment_intent_id
                     WHERE p.merchant_id = ? AND r.created_at >= ? AND r.created_at < ?
                     GROUP BY 1)
                SELECT days.day, COALESCE(c.gross, 0), COALESCE(c.fees, 0), COALESCE(c.net, 0),
                       COALESCE(r.refunds, 0), COALESCE(c.cnt, 0)
                  FROM days LEFT JOIN captured c ON c.day = days.day LEFT JOIN refunded r ON r.day = days.day
                 ORDER BY days.day
                """,
                (rs, i) -> new DailyPoint(
                        rs.getDate(1).toLocalDate(),
                        rs.getLong(2),
                        rs.getLong(3),
                        rs.getLong(4),
                        rs.getLong(5),
                        rs.getLong(6)),
                Date.valueOf(from),
                Date.valueOf(to),
                merchantId,
                Timestamp.from(from.atStartOfDay(ZoneOffset.UTC).toInstant()),
                Timestamp.from(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()),
                merchantId,
                Timestamp.from(from.atStartOfDay(ZoneOffset.UTC).toInstant()),
                Timestamp.from(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
    }

    /**
     * Simulated payout: Debit Merchant Payable, Credit Platform Funding Clearing (cash leaves the
     * platform). Rejected if the payable balance would go negative. Sandbox only.
     */
    @Transactional
    public IdempotencyService.Result<MerchantDtos.PayoutView> payout(
            AuthenticatedUser merchant, String key, MerchantDtos.PayoutRequest request) {
        if (!props.sandbox().enabled()) {
            throw new ApiException(ErrorCode.SANDBOX_DISABLED, "Sandbox payouts are disabled in this environment");
        }
        return idempotency.execute(
                new IdempotencyService.Scope(merchant.id(), "POST", "/api/v1/merchant/payouts", key),
                request,
                MerchantDtos.PayoutView.class,
                201,
                () -> doPayout(merchant, key, request),
                MerchantDtos.PayoutView::ledgerTransactionId);
    }

    private MerchantDtos.PayoutView doPayout(
            AuthenticatedUser merchant, String key, MerchantDtos.PayoutRequest request) {
        LedgerAccount payable = payableOf(merchant);
        LedgerAccount clearing = accounts.systemAccount(AccountPurpose.FUNDING_CLEARING, payable.getCurrency());
        UUID payoutId = UUID.randomUUID();
        LedgerService.PostedTransaction posted =
                ledger.post(LedgerPostingRequest.builder(LedgerTransactionType.PAYOUT, payable.getCurrency())
                        .description("Payout to linked bank account (sandbox)")
                        .debit(payable.getId(), request.amountMinor())
                        .credit(clearing.getId(), request.amountMinor())
                        .relatedEntity(payoutId)
                        .idempotencyKey(key)
                        .initiatedBy(merchant.id())
                        .build());
        Payout payout = payouts.save(new Payout(
                payoutId,
                ReferenceGenerator.next("PO"),
                merchant.id(),
                request.amountMinor(),
                payable.getCurrency(),
                posted.id(),
                clock.instant()));
        outbox.record(
                EventTypes.PAYOUT_PAID,
                "PAYOUT",
                payout.getId(),
                merchant.id(),
                Map.of(
                        "payoutId", payout.getId().toString(),
                        "reference", payout.getPublicReference(),
                        "amountMinor", payout.getAmountMinor(),
                        "currency", payout.getCurrency().name()));
        audit.record(
                merchant,
                "PAYOUT_CREATED",
                "PAYOUT",
                payout.getId(),
                AuditLog.Result.SUCCESS,
                Map.of("amountMinor", request.amountMinor()));
        return payoutView(payout, posted.balanceAfter(payable.getId()));
    }

    @Transactional(readOnly = true)
    public PageResponse<MerchantDtos.PayoutView> payouts(AuthenticatedUser merchant, PageParams page) {
        return PageResponse.from(
                payouts.findByMerchantIdOrderByCreatedAtDesc(merchant.id(), PageRequest.of(page.page(), page.size())),
                p -> payoutView(p, -1));
    }

    private static MerchantDtos.PayoutView payoutView(Payout p, long balanceAfter) {
        return new MerchantDtos.PayoutView(
                p.getId(),
                p.getPublicReference(),
                p.getAmountMinor(),
                p.getCurrency().name(),
                p.getStatus(),
                p.getLedgerTransactionId(),
                balanceAfter,
                p.getCreatedAt());
    }

    public Instant now() {
        return clock.instant();
    }
}
