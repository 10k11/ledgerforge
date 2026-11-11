package dev.ledgerforge.dashboard;

import dev.ledgerforge.common.error.ApiException;
import dev.ledgerforge.common.security.AuthenticatedUser;
import dev.ledgerforge.ledger.AccountPurpose;
import dev.ledgerforge.ledger.LedgerAccount;
import dev.ledgerforge.ledger.LedgerAccountService;
import dev.ledgerforge.ledger.LedgerService;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Overview metrics for the caller's primary account (customer wallet or merchant payable), all
 * derived from ledger entries and payment intents. Nothing here is estimated or hard-coded.
 */
@Service
public class DashboardService {

    private final LedgerAccountService accounts;
    private final LedgerService ledger;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DashboardService(LedgerAccountService accounts, LedgerService ledger, JdbcTemplate jdbc, Clock clock) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record TypeTotal(String type, long inMinor, long outMinor, long count) {}

    public record DayPoint(LocalDate date, long inMinor, long outMinor) {}

    public record Summary(
            String accountNumber,
            String accountKind,
            String currency,
            long balanceMinor,
            long totalInMinor,
            long totalOutMinor,
            List<TypeTotal> byType,
            long successfulPayments,
            long pendingPayments,
            long transactionCount,
            List<DayPoint> last30Days) {}

    @Transactional(readOnly = true)
    public Summary summary(AuthenticatedUser user) {
        LedgerAccount account = accounts.accountsOwnedBy(user.id()).stream()
                .filter(a -> a.getPurpose() == AccountPurpose.CUSTOMER_WALLET
                        || a.getPurpose() == AccountPurpose.MERCHANT_PAYABLE)
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Account"));
        UUID accountId = account.getId();
        String signedIn = "CASE WHEN e.entry_type = a.normal_balance THEN e.amount_minor ELSE 0 END";
        String signedOut = "CASE WHEN e.entry_type <> a.normal_balance THEN e.amount_minor ELSE 0 END";
        List<TypeTotal> byType = jdbc.query(
                "SELECT t.transaction_type, COALESCE(SUM(" + signedIn + "), 0), COALESCE(SUM(" + signedOut + "), 0),"
                        + " COUNT(DISTINCT t.id)"
                        + " FROM ledger_entries e JOIN ledger_accounts a ON a.id = e.ledger_account_id"
                        + " JOIN ledger_transactions t ON t.id = e.ledger_transaction_id"
                        + " WHERE e.ledger_account_id = ? GROUP BY t.transaction_type ORDER BY t.transaction_type",
                (rs, i) -> new TypeTotal(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4)),
                accountId);
        String partyColumn = account.getPurpose() == AccountPurpose.CUSTOMER_WALLET ? "customer_id" : "merchant_id";
        Long successful = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_intents WHERE " + partyColumn + " = ?"
                        + " AND status IN ('SUCCEEDED', 'PARTIALLY_REFUNDED', 'REFUNDED')",
                Long.class,
                user.id());
        Long pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_intents WHERE " + partyColumn
                        + " = ? AND status IN ('CREATED', 'PROCESSING')",
                Long.class,
                user.id());
        LocalDate today = LocalDate.now(clock);
        LocalDate start = today.minusDays(29);
        List<DayPoint> series = jdbc.query(
                "WITH days AS (SELECT d::date AS day FROM generate_series(?::date, ?::date, interval '1 day') d),"
                        + " flows AS (SELECT (e.created_at AT TIME ZONE 'UTC')::date AS day,"
                        + "   SUM(" + signedIn + ") AS inflow, SUM(" + signedOut + ") AS outflow"
                        + "   FROM ledger_entries e JOIN ledger_accounts a ON a.id = e.ledger_account_id"
                        + "  WHERE e.ledger_account_id = ? AND e.created_at >= ? GROUP BY 1)"
                        + " SELECT days.day, COALESCE(f.inflow, 0), COALESCE(f.outflow, 0)"
                        + " FROM days LEFT JOIN flows f ON f.day = days.day ORDER BY days.day",
                (rs, i) -> new DayPoint(rs.getDate(1).toLocalDate(), rs.getLong(2), rs.getLong(3)),
                Date.valueOf(start),
                Date.valueOf(today),
                accountId,
                Timestamp.from(start.atStartOfDay(ZoneOffset.UTC).toInstant()));
        return new Summary(
                account.getAccountNumber(),
                account.getPurpose().name(),
                account.getCurrency().name(),
                ledger.balanceOf(accountId),
                byType.stream().mapToLong(TypeTotal::inMinor).sum(),
                byType.stream().mapToLong(TypeTotal::outMinor).sum(),
                byType,
                successful == null ? 0 : successful,
                pending == null ? 0 : pending,
                byType.stream().mapToLong(TypeTotal::count).sum(),
                series);
    }
}
