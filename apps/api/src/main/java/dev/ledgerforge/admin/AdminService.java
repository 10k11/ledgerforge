package dev.ledgerforge.admin;

import dev.ledgerforge.audit.AuditLog;
import dev.ledgerforge.audit.AuditService;
import dev.ledgerforge.common.error.ApiException;
import dev.ledgerforge.common.security.AuthenticatedUser;
import dev.ledgerforge.idempotency.IdempotencyService;
import dev.ledgerforge.ledger.LedgerEntry;
import dev.ledgerforge.ledger.LedgerEntryRepository;
import dev.ledgerforge.ledger.LedgerPostingRequest;
import dev.ledgerforge.ledger.LedgerService;
import dev.ledgerforge.ledger.LedgerTransaction;
import dev.ledgerforge.ledger.LedgerTransactionRepository;
import dev.ledgerforge.ledger.LedgerTransactionType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminService {

    private final JdbcTemplate jdbc;
    private final LedgerTransactionRepository transactions;
    private final LedgerEntryRepository entries;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final AuditService audit;

    public AdminService(
            JdbcTemplate jdbc,
            LedgerTransactionRepository transactions,
            LedgerEntryRepository entries,
            LedgerService ledger,
            IdempotencyService idempotency,
            AuditService audit) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.entries = entries;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    public record AccountView(
            UUID id,
            String accountNumber,
            String name,
            String type,
            String purpose,
            String currency,
            long balanceMinor) {}

    public record CurrencyVolume(
            String currency,
            long capturedMinor,
            long feesMinor,
            long refundedMinor,
            long walletLiabilitiesMinor,
            long merchantLiabilitiesMinor) {}

    public record Overview(
            Map<String, Long> usersByRole,
            long ledgerTransactions,
            long ledgerEntries,
            Map<String, Long> paymentsByStatus,
            List<CurrencyVolume> volumes,
            List<AccountView> systemAccounts,
            Map<String, Long> webhookDeliveriesByStatus) {}

    @Transactional(readOnly = true)
    public Overview overview() {
        return new Overview(
                countBy("SELECT role, COUNT(*) FROM users GROUP BY role"),
                count("SELECT COUNT(*) FROM ledger_transactions"),
                count("SELECT COUNT(*) FROM ledger_entries"),
                countBy("SELECT status, COUNT(*) FROM payment_intents GROUP BY status"),
                jdbc.query(
                        """
                        SELECT c.currency,
                               COALESCE((SELECT SUM(amount_minor) FROM payment_intents p
                                          WHERE p.currency = c.currency AND p.captured_at IS NOT NULL), 0),
                               COALESCE((SELECT SUM(platform_fee_minor - refunded_fee_minor) FROM payment_intents p
                                          WHERE p.currency = c.currency AND p.captured_at IS NOT NULL), 0),
                               COALESCE((SELECT SUM(refunded_amount_minor) FROM payment_intents p
                                          WHERE p.currency = c.currency), 0),
                               COALESCE((SELECT SUM(b.balance_minor) FROM ledger_accounts a
                                           JOIN account_balances b ON b.ledger_account_id = a.id
                                          WHERE a.currency = c.currency AND a.account_purpose = 'CUSTOMER_WALLET'), 0),
                               COALESCE((SELECT SUM(b.balance_minor) FROM ledger_accounts a
                                           JOIN account_balances b ON b.ledger_account_id = a.id
                                          WHERE a.currency = c.currency AND a.account_purpose = 'MERCHANT_PAYABLE'), 0)
                          FROM (VALUES ('INR'), ('USD')) AS c(currency)
                        """,
                        (rs, i) -> new CurrencyVolume(
                                rs.getString(1),
                                rs.getLong(2),
                                rs.getLong(3),
                                rs.getLong(4),
                                rs.getLong(5),
                                rs.getLong(6))),
                jdbc.query(
                        """
                        SELECT a.id, a.account_number, a.account_name, a.account_type, a.account_purpose, a.currency,
                               b.balance_minor
                          FROM ledger_accounts a JOIN account_balances b ON b.ledger_account_id = a.id
                         WHERE a.owner_id IS NULL ORDER BY a.currency, a.account_number
                        """,
                        (rs, i) -> new AccountView(
                                rs.getObject(1, UUID.class),
                                rs.getString(2),
                                rs.getString(3),
                                rs.getString(4),
                                rs.getString(5),
                                rs.getString(6),
                                rs.getLong(7))),
                countBy("SELECT status, COUNT(*) FROM webhook_deliveries GROUP BY status"));
    }

    public record ReversalView(UUID transactionId, String reference, UUID reversesTransactionId, Instant createdAt) {}

    /**
     * Posts the exact mirror of a TOP_UP or TRANSFER. The ledger's reversal invariants ensure it can
     * be applied at most once in full, and balance floors ensure it cannot overdraw a wallet.
     */
    @Transactional
    public IdempotencyService.Result<ReversalView> reverse(
            AuthenticatedUser adminUser, UUID transactionId, String key, String reason) {
        return idempotency.execute(
                new IdempotencyService.Scope(
                        adminUser.id(),
                        "POST",
                        "/api/v1/admin/ledger/transactions/" + transactionId + "/reversal",
                        key),
                Map.of("reason", reason),
                ReversalView.class,
                201,
                () -> {
                    LedgerTransaction original = transactions
                            .findById(transactionId)
                            .orElseThrow(() -> ApiException.notFound("Transaction"));
                    if (original.getTransactionType() != LedgerTransactionType.TOP_UP
                            && original.getTransactionType() != LedgerTransactionType.TRANSFER) {
                        throw ApiException.invalidState(
                                "Only top-ups and transfers can be reversed here;" + " use refunds for payments");
                    }
                    LedgerPostingRequest.Builder builder = LedgerPostingRequest.builder(
                                    LedgerTransactionType.REVERSAL, original.getCurrency())
                            .description("Reversal of " + original.getReferenceNumber() + ": " + reason)
                            .reverses(original.getId())
                            .idempotencyKey(key)
                            .initiatedBy(adminUser.id());
                    for (LedgerEntry e : entries.findByTransactionId(original.getId())) {
                        if (e.getEntryType() == dev.ledgerforge.ledger.EntryType.DEBIT) {
                            builder.credit(e.getLedgerAccountId(), e.getAmountMinor());
                        } else {
                            builder.debit(e.getLedgerAccountId(), e.getAmountMinor());
                        }
                    }
                    LedgerService.PostedTransaction posted = ledger.post(builder.build());
                    audit.record(
                            adminUser,
                            "LEDGER_REVERSAL",
                            "LEDGER_TRANSACTION",
                            posted.id(),
                            AuditLog.Result.SUCCESS,
                            Map.of("original", original.getReferenceNumber(), "reason", reason));
                    return new ReversalView(
                            posted.id(),
                            posted.reference(),
                            original.getId(),
                            posted.transaction().getCreatedAt());
                },
                ReversalView::transactionId);
    }

    private long count(String sql) {
        Long v = jdbc.queryForObject(sql, Long.class);
        return v == null ? 0 : v;
    }

    private Map<String, Long> countBy(String sql) {
        Map<String, Long> out = new java.util.TreeMap<>();
        jdbc.query(sql, rs -> {
            out.put(rs.getString(1), rs.getLong(2));
        });
        return out;
    }
}
