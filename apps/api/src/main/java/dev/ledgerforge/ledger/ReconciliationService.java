package dev.ledgerforge.ledger;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies that the balance projection agrees with the ledger and that the ledger itself is
 * internally consistent. Runs in a REPEATABLE READ snapshot so all checks see the same state.
 */
@Service
public class ReconciliationService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ReconciliationService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record AccountMismatch(
            String accountId, String accountNumber, long projectedBalanceMinor, long ledgerBalanceMinor) {}

    public record CurrencyTrialBalance(
            String currency,
            long totalDebitsMinor,
            long totalCreditsMinor,
            long assetsMinor,
            long liabilitiesMinor,
            long equityMinor,
            long revenueMinor,
            long expensesMinor,
            boolean balanced,
            boolean accountingEquationHolds) {}

    public record Report(
            Instant checkedAt,
            boolean healthy,
            long accountsChecked,
            long transactionsChecked,
            List<AccountMismatch> balanceMismatches,
            List<String> unbalancedTransactions,
            List<CurrencyTrialBalance> trialBalances) {}

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report reconcile() {
        List<AccountMismatch> mismatches = jdbc.query(
                """
                SELECT a.id, a.account_number, b.balance_minor,
                       COALESCE(SUM(CASE WHEN e.entry_type = a.normal_balance THEN e.amount_minor
                                         ELSE -e.amount_minor END), 0) AS ledger_balance
                  FROM ledger_accounts a
                  JOIN account_balances b ON b.ledger_account_id = a.id
                  LEFT JOIN ledger_entries e ON e.ledger_account_id = a.id
                 GROUP BY a.id, a.account_number, b.balance_minor
                HAVING b.balance_minor <> COALESCE(SUM(CASE WHEN e.entry_type = a.normal_balance
                                                            THEN e.amount_minor ELSE -e.amount_minor END), 0)
                """, (rs, i) -> new AccountMismatch(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)));

        List<String> unbalanced = jdbc.queryForList("""
                SELECT t.reference_number
                  FROM ledger_transactions t
                  LEFT JOIN ledger_entries e ON e.ledger_transaction_id = t.id
                 GROUP BY t.id, t.reference_number
                HAVING COUNT(e.id) < 2
                    OR COALESCE(SUM(e.amount_minor) FILTER (WHERE e.entry_type = 'DEBIT'), 0)
                    <> COALESCE(SUM(e.amount_minor) FILTER (WHERE e.entry_type = 'CREDIT'), 0)
                """, String.class);

        List<CurrencyTrialBalance> trial = jdbc.query("""
                WITH entry_totals AS (
                    SELECT currency,
                           COALESCE(SUM(amount_minor) FILTER (WHERE entry_type = 'DEBIT'), 0)  AS debits,
                           COALESCE(SUM(amount_minor) FILTER (WHERE entry_type = 'CREDIT'), 0) AS credits
                      FROM ledger_entries GROUP BY currency),
                balance_totals AS (
                    SELECT a.currency,
                           COALESCE(SUM(b.balance_minor) FILTER (WHERE a.account_type = 'ASSET'), 0)     AS assets,
                           COALESCE(SUM(b.balance_minor) FILTER (WHERE a.account_type = 'LIABILITY'), 0) AS liabilities,
                           COALESCE(SUM(b.balance_minor) FILTER (WHERE a.account_type = 'EQUITY'), 0)    AS equity,
                           COALESCE(SUM(b.balance_minor) FILTER (WHERE a.account_type = 'REVENUE'), 0)   AS revenue,
                           COALESCE(SUM(b.balance_minor) FILTER (WHERE a.account_type = 'EXPENSE'), 0)   AS expenses
                      FROM ledger_accounts a JOIN account_balances b ON b.ledger_account_id = a.id
                     GROUP BY a.currency)
                SELECT bt.currency, COALESCE(et.debits, 0), COALESCE(et.credits, 0),
                       bt.assets, bt.liabilities, bt.equity, bt.revenue, bt.expenses
                  FROM balance_totals bt LEFT JOIN entry_totals et ON et.currency = bt.currency
                 ORDER BY bt.currency
                """, (rs, i) -> {
            long debits = rs.getLong(2);
            long credits = rs.getLong(3);
            long assets = rs.getLong(4);
            long liabilities = rs.getLong(5);
            long equity = rs.getLong(6);
            long revenue = rs.getLong(7);
            long expenses = rs.getLong(8);
            return new CurrencyTrialBalance(
                    rs.getString(1),
                    debits,
                    credits,
                    assets,
                    liabilities,
                    equity,
                    revenue,
                    expenses,
                    debits == credits,
                    assets + expenses == liabilities + equity + revenue);
        });

        Long accountCount = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_accounts", Long.class);
        Long txCount = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_transactions", Long.class);
        boolean healthy = mismatches.isEmpty()
                && unbalanced.isEmpty()
                && trial.stream().allMatch(t -> t.balanced() && t.accountingEquationHolds());
        return new Report(
                clock.instant(),
                healthy,
                accountCount == null ? 0 : accountCount,
                txCount == null ? 0 : txCount,
                mismatches,
                unbalanced,
                trial);
    }
}
