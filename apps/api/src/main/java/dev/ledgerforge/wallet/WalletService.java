package dev.ledgerforge.wallet;

import dev.ledgerforge.audit.AuditLog;
import dev.ledgerforge.audit.AuditService;
import dev.ledgerforge.common.config.AppProperties;
import dev.ledgerforge.common.error.ApiException;
import dev.ledgerforge.common.error.ErrorCode;
import dev.ledgerforge.common.money.Currency;
import dev.ledgerforge.common.security.AuthenticatedUser;
import dev.ledgerforge.common.web.PageParams;
import dev.ledgerforge.idempotency.IdempotencyService;
import dev.ledgerforge.ledger.AccountPurpose;
import dev.ledgerforge.ledger.LedgerAccount;
import dev.ledgerforge.ledger.LedgerAccountService;
import dev.ledgerforge.ledger.LedgerPostingRequest;
import dev.ledgerforge.ledger.LedgerService;
import dev.ledgerforge.ledger.LedgerTransactionType;
import dev.ledgerforge.wallet.WalletDtos.Statement;
import dev.ledgerforge.wallet.WalletDtos.StatementLine;
import dev.ledgerforge.wallet.WalletDtos.TopUpRequest;
import dev.ledgerforge.wallet.WalletDtos.TopUpResponse;
import dev.ledgerforge.wallet.WalletDtos.WalletView;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletService {

    private static final Duration MAX_STATEMENT_RANGE = Duration.ofDays(366);

    private final LedgerAccountService accounts;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final AppProperties props;
    private final Clock clock;

    public WalletService(
            LedgerAccountService accounts,
            LedgerService ledger,
            IdempotencyService idempotency,
            AuditService audit,
            JdbcTemplate jdbc,
            AppProperties props,
            Clock clock) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.audit = audit;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    /** The caller's wallet. Ownership is implicit: the account is looked up by the token's user id. */
    @Transactional(readOnly = true)
    public LedgerAccount walletOf(AuthenticatedUser user) {
        return accounts.accountsOwnedBy(user.id()).stream()
                .filter(a -> a.getPurpose() == AccountPurpose.CUSTOMER_WALLET)
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Wallet"));
    }

    @Transactional(readOnly = true)
    public WalletView wallet(AuthenticatedUser user) {
        LedgerAccount wallet = walletOf(user);
        return new WalletView(
                wallet.getId(),
                wallet.getAccountNumber(),
                wallet.getAccountName(),
                wallet.getCurrency().name(),
                ledger.balanceOf(wallet.getId()),
                wallet.getStatus().name(),
                props.sandbox().enabled(),
                new WalletDtos.TopUpLimits(
                        props.sandbox().maxTopUpMinor(),
                        props.sandbox().dailyTopUpLimitMinor(),
                        toppedUpToday(wallet.getId())),
                wallet.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public WalletDtos.BalanceView balance(AuthenticatedUser user) {
        LedgerAccount wallet = walletOf(user);
        return new WalletDtos.BalanceView(
                wallet.getAccountNumber(),
                wallet.getCurrency().name(),
                ledger.balanceOf(wallet.getId()),
                clock.instant());
    }

    /**
     * Sandbox top-up: Debit Platform Funding Clearing (asset), Credit Customer Wallet (liability).
     * Disabled entirely when {@code app.sandbox.enabled=false} (always the case in production).
     */
    @Transactional
    public IdempotencyService.Result<TopUpResponse> topUp(AuthenticatedUser user, String key, TopUpRequest request) {
        if (!props.sandbox().enabled()) {
            throw new ApiException(ErrorCode.SANDBOX_DISABLED, "Sandbox top-ups are disabled in this environment");
        }
        return idempotency.execute(
                new IdempotencyService.Scope(user.id(), "POST", "/api/v1/wallet/sandbox/top-ups", key),
                request,
                TopUpResponse.class,
                201,
                () -> doTopUp(user, key, request),
                TopUpResponse::transactionId);
    }

    private TopUpResponse doTopUp(AuthenticatedUser user, String key, TopUpRequest request) {
        Currency currency = Currency.parse(request.currency());
        LedgerAccount wallet = walletOf(user);
        if (wallet.getCurrency() != currency) {
            throw new ApiException(
                    ErrorCode.CURRENCY_MISMATCH, "Your wallet is denominated in " + wallet.getCurrency());
        }
        long amount = request.amountMinor();
        if (amount > props.sandbox().maxTopUpMinor()) {
            throw new ApiException(
                    ErrorCode.LIMIT_EXCEEDED,
                    "A single sandbox top-up is limited to " + props.sandbox().maxTopUpMinor() + " minor units",
                    Map.of("maxPerTopUpMinor", props.sandbox().maxTopUpMinor()));
        }
        LedgerAccount funding = accounts.systemAccount(AccountPurpose.FUNDING_CLEARING, currency);
        // Lock first (same deterministic order the ledger uses), so the daily-limit check below
        // cannot race with a concurrent top-up of the same wallet.
        ledger.lockBalances(List.of(wallet.getId(), funding.getId()));
        long usedToday = toppedUpToday(wallet.getId());
        if (Math.addExact(usedToday, amount) > props.sandbox().dailyTopUpLimitMinor()) {
            throw new ApiException(
                    ErrorCode.LIMIT_EXCEEDED,
                    "Daily sandbox top-up limit reached",
                    Map.of("dailyLimitMinor", props.sandbox().dailyTopUpLimitMinor(), "usedTodayMinor", usedToday));
        }
        LedgerService.PostedTransaction posted =
                ledger.post(LedgerPostingRequest.builder(LedgerTransactionType.TOP_UP, currency)
                        .description("Sandbox wallet top-up")
                        .debit(funding.getId(), amount)
                        .credit(wallet.getId(), amount)
                        .idempotencyKey(key)
                        .initiatedBy(user.id())
                        .build());
        audit.record(
                user,
                "WALLET_TOP_UP",
                "LEDGER_TRANSACTION",
                posted.id(),
                AuditLog.Result.SUCCESS,
                Map.of("amountMinor", amount, "currency", currency.name()));
        return new TopUpResponse(
                posted.id(),
                posted.reference(),
                amount,
                currency.name(),
                posted.balanceAfter(wallet.getId()),
                posted.transaction().getCreatedAt());
    }

    private long toppedUpToday(UUID walletId) {
        Instant startOfDay = LocalDate.now(clock).atStartOfDay(ZoneOffset.UTC).toInstant();
        Long sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(e.amount_minor), 0)
                  FROM ledger_entries e JOIN ledger_transactions t ON t.id = e.ledger_transaction_id
                 WHERE e.ledger_account_id = ? AND t.transaction_type = 'TOP_UP'
                   AND e.entry_type = 'CREDIT' AND t.created_at >= ?
                """, Long.class, walletId, Timestamp.from(startOfDay));
        return sum == null ? 0 : sum;
    }

    /**
     * Account statement for [from, to): opening balance, entries with running balance, closing
     * balance. Computed entirely in SQL with window functions; only one page of lines is returned.
     */
    @Transactional(readOnly = true)
    public Statement statement(AuthenticatedUser user, Instant from, Instant to, PageParams page) {
        LedgerAccount wallet = walletOf(user);
        return statementFor(wallet, from, to, page);
    }

    @Transactional(readOnly = true)
    public Statement statementFor(LedgerAccount account, Instant from, Instant to, PageParams page) {
        Instant end = to == null ? clock.instant().plusSeconds(1) : to;
        Instant start = from == null ? end.minus(Duration.ofDays(30)) : from;
        if (!start.isBefore(end)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "'from' must be before 'to'");
        }
        if (Duration.between(start, end).compareTo(MAX_STATEMENT_RANGE) > 0) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Statement range is limited to 366 days");
        }
        String signed = "CASE WHEN e.entry_type = a.normal_balance THEN e.amount_minor ELSE -e.amount_minor END";
        Long opening = jdbc.queryForObject(
                "SELECT COALESCE(SUM(" + signed + "), 0) FROM ledger_entries e"
                        + " JOIN ledger_accounts a ON a.id = e.ledger_account_id"
                        + " WHERE e.ledger_account_id = ? AND e.created_at < ?",
                Long.class,
                account.getId(),
                Timestamp.from(start));
        long openingBalance = opening == null ? 0 : opening;

        List<long[]> totals = jdbc.query(
                "SELECT COALESCE(SUM(e.amount_minor) FILTER (WHERE e.entry_type = a.normal_balance), 0),"
                        + " COALESCE(SUM(e.amount_minor) FILTER (WHERE e.entry_type <> a.normal_balance), 0),"
                        + " COUNT(*)"
                        + " FROM ledger_entries e JOIN ledger_accounts a ON a.id = e.ledger_account_id"
                        + " WHERE e.ledger_account_id = ? AND e.created_at >= ? AND e.created_at < ?",
                (rs, i) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)},
                account.getId(),
                Timestamp.from(start),
                Timestamp.from(end));
        long increases = totals.getFirst()[0];
        long decreases = totals.getFirst()[1];
        long count = totals.getFirst()[2];

        List<StatementLine> lines = jdbc.query(
                "SELECT * FROM ("
                        + " SELECT t.id, t.reference_number, t.transaction_type, t.description, e.entry_type,"
                        + "        e.amount_minor, e.created_at, e.sequence_number,"
                        + "        ? + SUM(" + signed + ") OVER (ORDER BY e.sequence_number) AS running"
                        + "   FROM ledger_entries e"
                        + "   JOIN ledger_accounts a ON a.id = e.ledger_account_id"
                        + "   JOIN ledger_transactions t ON t.id = e.ledger_transaction_id"
                        + "  WHERE e.ledger_account_id = ? AND e.created_at >= ? AND e.created_at < ?"
                        + " ) s ORDER BY s.sequence_number DESC LIMIT ? OFFSET ?",
                (rs, i) -> new StatementLine(
                        rs.getObject(1, UUID.class),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5).equals(account.getNormalBalance().name()) ? "IN" : "OUT",
                        rs.getLong(6),
                        rs.getLong(9),
                        rs.getTimestamp(7).toInstant()),
                openingBalance,
                account.getId(),
                Timestamp.from(start),
                Timestamp.from(end),
                page.size(),
                page.offset());

        // Credits increase a liability wallet; for statement purposes "credits" = money in.
        return new Statement(
                account.getAccountNumber(),
                account.getAccountName(),
                account.getCurrency().name(),
                start,
                end,
                openingBalance,
                openingBalance + increases - decreases,
                increases,
                decreases,
                lines,
                page.page(),
                page.size(),
                count,
                (int) ((count + page.size() - 1) / page.size()));
    }
}
