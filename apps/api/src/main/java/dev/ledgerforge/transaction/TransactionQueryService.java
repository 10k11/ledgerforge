package dev.ledgerforge.transaction;

import dev.ledgerforge.common.error.ApiException;
import dev.ledgerforge.common.error.ErrorCode;
import dev.ledgerforge.common.money.Currency;
import dev.ledgerforge.common.security.AuthenticatedUser;
import dev.ledgerforge.common.web.PageParams;
import dev.ledgerforge.common.web.PageResponse;
import dev.ledgerforge.ledger.AccountPurpose;
import dev.ledgerforge.ledger.LedgerTransactionType;
import dev.ledgerforge.transaction.TransactionDtos.LedgerLine;
import dev.ledgerforge.transaction.TransactionDtos.LedgerView;
import dev.ledgerforge.transaction.TransactionDtos.RelatedEntity;
import dev.ledgerforge.transaction.TransactionDtos.TransactionDetail;
import dev.ledgerforge.transaction.TransactionDtos.TransactionSummary;
import dev.ledgerforge.user.UserDirectory;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read model over the ledger: a user's transaction history is the set of ledger transactions that
 * touch an account they own. Filtering, sorting and pagination happen in PostgreSQL; only one page
 * is ever loaded into memory.
 */
@Service
public class TransactionQueryService {

    private final NamedParameterJdbcTemplate jdbc;
    private final UserDirectory directory;

    public TransactionQueryService(NamedParameterJdbcTemplate jdbc, UserDirectory directory) {
        this.jdbc = jdbc;
        this.directory = directory;
    }

    public record Filter(
            String type, String currency, String direction, String q, Instant from, Instant to, String sort) {}

    private static final String NET =
            "SUM(CASE WHEN e.entry_type = a.normal_balance THEN e.amount_minor" + " ELSE -e.amount_minor END)";

    @Transactional(readOnly = true)
    public PageResponse<TransactionSummary> list(AuthenticatedUser user, Filter filter, PageParams page) {
        MapSqlParameterSource params = new MapSqlParameterSource("ownerId", user.id());
        StringBuilder where = new StringBuilder(" WHERE a.owner_id = :ownerId");
        if (filter.type() != null && !filter.type().isBlank()) {
            params.addValue("type", parseType(filter.type()).name());
            where.append(" AND t.transaction_type = :type");
        }
        if (filter.currency() != null && !filter.currency().isBlank()) {
            params.addValue("currency", Currency.parse(filter.currency()).name());
            where.append(" AND t.currency = :currency");
        }
        if (filter.from() != null) {
            params.addValue("from", Timestamp.from(filter.from()));
            where.append(" AND t.created_at >= :from");
        }
        if (filter.to() != null) {
            params.addValue("to", Timestamp.from(filter.to()));
            where.append(" AND t.created_at < :to");
        }
        if (filter.q() != null && !filter.q().isBlank()) {
            params.addValue("q", "%" + filter.q().trim().toLowerCase() + "%");
            where.append(" AND (lower(t.reference_number) LIKE :q OR lower(t.description) LIKE :q)");
        }
        String having = "";
        if (filter.direction() != null && !filter.direction().isBlank()) {
            having = switch (filter.direction().toUpperCase()) {
                case "IN" -> " HAVING " + NET + " > 0";
                case "OUT" -> " HAVING " + NET + " < 0";
                default -> throw new ApiException(ErrorCode.VALIDATION_FAILED, "direction must be IN or OUT");
            };
        }
        String orderBy = switch (filter.sort() == null ? "createdAt,desc" : filter.sort()) {
            case "createdAt,asc" -> " ORDER BY t.created_at ASC, t.id";
            case "amount,desc" -> " ORDER BY ABS(" + NET + ") DESC, t.created_at DESC";
            case "amount,asc" -> " ORDER BY ABS(" + NET + ") ASC, t.created_at DESC";
            default -> " ORDER BY t.created_at DESC, t.id";
        };
        String base = " FROM ledger_entries e"
                + " JOIN ledger_accounts a ON a.id = e.ledger_account_id"
                + " JOIN ledger_transactions t ON t.id = e.ledger_transaction_id"
                + where
                + " GROUP BY t.id"
                + having;
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT t.id" + base + ") x", params, Long.class);
        params.addValue("limit", page.size()).addValue("offset", page.offset());
        List<Row> rows = jdbc.query(
                "SELECT t.id, t.reference_number, t.transaction_type, t.status, t.description, t.currency,"
                        + " t.created_at, t.related_entity_id, " + NET + " AS net"
                        + base + orderBy + " LIMIT :limit OFFSET :offset",
                params,
                (rs, i) -> new Row(
                        rs.getObject(1, UUID.class),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6),
                        rs.getTimestamp(7).toInstant(),
                        rs.getObject(8, UUID.class),
                        rs.getLong(9)));
        Map<UUID, String> counterparties = counterparties(rows, user.id());
        List<TransactionSummary> items = rows.stream()
                .map(r -> new TransactionSummary(
                        r.id(),
                        r.reference(),
                        r.type(),
                        r.status(),
                        r.description(),
                        counterparties.get(r.id()),
                        r.currency(),
                        Math.abs(r.net()),
                        r.net() >= 0 ? "IN" : "OUT",
                        r.createdAt()))
                .toList();
        return PageResponse.of(items, page.page(), page.size(), total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public TransactionDetail detail(AuthenticatedUser user, UUID transactionId) {
        Row row = loadVisible(user, transactionId);
        Map<String, Object> meta = jdbc.queryForMap(
                "SELECT t.idempotency_key, t.initiated_by, t.reverses_transaction_id, t.posted_at,"
                        + " (SELECT reference_number FROM ledger_transactions o WHERE o.id = t.reverses_transaction_id)"
                        + " AS reverses_ref FROM ledger_transactions t WHERE t.id = :id",
                new MapSqlParameterSource("id", transactionId));
        List<UUID> reversedBy = jdbc.queryForList(
                "SELECT id FROM ledger_transactions WHERE reverses_transaction_id = :id ORDER BY created_at",
                new MapSqlParameterSource("id", transactionId),
                UUID.class);
        // The idempotency key is disclosed only to the user who submitted the request.
        String idempotencyKey =
                user.id().equals(meta.get("initiated_by")) ? (String) meta.get("idempotency_key") : null;
        return new TransactionDetail(
                row.id(),
                row.reference(),
                row.type(),
                row.status(),
                row.description(),
                counterparties(List.of(row), user.id()).get(row.id()),
                row.currency(),
                Math.abs(row.net()),
                row.net() >= 0 ? "IN" : "OUT",
                row.createdAt(),
                ((Timestamp) meta.get("posted_at")).toInstant(),
                idempotencyKey,
                related(row),
                (UUID) meta.get("reverses_transaction_id"),
                (String) meta.get("reverses_ref"),
                reversedBy);
    }

    @Transactional(readOnly = true)
    public LedgerView ledger(AuthenticatedUser user, UUID transactionId) {
        Row row = loadVisible(user, transactionId);
        List<LedgerLine> lines = jdbc.query("""
                SELECT e.id, e.sequence_number, a.account_purpose, a.account_number, a.account_type, e.entry_type,
                       e.amount_minor, a.owner_id, a.account_name
                  FROM ledger_entries e JOIN ledger_accounts a ON a.id = e.ledger_account_id
                 WHERE e.ledger_transaction_id = :id
                 ORDER BY CASE e.entry_type WHEN 'DEBIT' THEN 0 ELSE 1 END, e.sequence_number
                """, new MapSqlParameterSource("id", transactionId), (rs, i) -> {
            UUID owner = rs.getObject(8, UUID.class);
            boolean mine = user.id().equals(owner);
            AccountPurpose purpose = AccountPurpose.valueOf(rs.getString(3));
            String number = rs.getString(4);
            boolean revealAccount = mine || owner == null || user.isAdmin();
            String label = purpose.label() + (mine ? " (you)" : "");
            if (user.isAdmin() && owner != null) {
                label = rs.getString(9);
            }
            long amount = rs.getLong(7);
            boolean debit = "DEBIT".equals(rs.getString(6));
            return new LedgerLine(
                    rs.getObject(1, UUID.class),
                    rs.getLong(2),
                    label,
                    revealAccount ? number : "••••" + number.substring(number.length() - 4),
                    rs.getString(5),
                    rs.getString(6),
                    debit ? amount : 0,
                    debit ? 0 : amount,
                    mine);
        });
        long debits = lines.stream().mapToLong(LedgerLine::debitMinor).sum();
        long credits = lines.stream().mapToLong(LedgerLine::creditMinor).sum();
        Timestamp postedAt = jdbc.queryForObject(
                "SELECT posted_at FROM ledger_transactions WHERE id = :id",
                new MapSqlParameterSource("id", transactionId),
                Timestamp.class);
        return new LedgerView(
                row.id(),
                row.reference(),
                row.currency(),
                row.status(),
                lines,
                debits,
                credits,
                debits == credits && lines.size() >= 2,
                postedAt == null ? null : postedAt.toInstant());
    }

    // ------------------------------------------------------------------ helpers

    private record Row(
            UUID id,
            String reference,
            String type,
            String status,
            String description,
            String currency,
            Instant createdAt,
            UUID relatedEntityId,
            long net) {}

    /**
     * Loads a transaction only if it touches an account owned by the viewer (admins see all).
     * Anything else is reported as 404 so other users' transaction ids cannot be probed.
     */
    private Row loadVisible(AuthenticatedUser user, UUID id) {
        MapSqlParameterSource params = new MapSqlParameterSource("id", id).addValue("ownerId", user.id());
        String ownerClause = user.isAdmin() ? "" : " AND a.owner_id = :ownerId";
        // For admins viewing system postings, the "net" is taken across all accounts' debit side.
        String net = user.isAdmin() ? "COALESCE(SUM(e.amount_minor) FILTER (WHERE e.entry_type = 'DEBIT'), 0)" : NET;
        List<Row> rows = jdbc.query(
                "SELECT t.id, t.reference_number, t.transaction_type, t.status, t.description, t.currency,"
                        + " t.created_at, t.related_entity_id, " + net
                        + " FROM ledger_transactions t"
                        + " JOIN ledger_entries e ON e.ledger_transaction_id = t.id"
                        + " JOIN ledger_accounts a ON a.id = e.ledger_account_id"
                        + " WHERE t.id = :id" + ownerClause
                        + " GROUP BY t.id",
                params,
                (rs, i) -> new Row(
                        rs.getObject(1, UUID.class),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6),
                        rs.getTimestamp(7).toInstant(),
                        rs.getObject(8, UUID.class),
                        rs.getLong(9)));
        if (rows.isEmpty()) {
            throw ApiException.notFound("Transaction");
        }
        return rows.getFirst();
    }

    private RelatedEntity related(Row row) {
        if (row.relatedEntityId() == null) {
            return null;
        }
        MapSqlParameterSource p = new MapSqlParameterSource("id", row.relatedEntityId());
        return switch (LedgerTransactionType.valueOf(row.type())) {
            case TRANSFER -> single("SELECT 'TRANSFER', id, public_reference, status FROM transfers WHERE id = :id", p);
            case PAYMENT ->
                single("SELECT 'PAYMENT', id, public_reference, status FROM payment_intents WHERE id = :id", p);
            case REFUND ->
                single(
                        "SELECT 'PAYMENT', p.id, p.public_reference, p.status FROM refunds r"
                                + " JOIN payment_intents p ON p.id = r.payment_intent_id WHERE r.id = :id",
                        p);
            case PAYOUT -> single("SELECT 'PAYOUT', id, public_reference, status FROM payouts WHERE id = :id", p);
            default -> null;
        };
    }

    private RelatedEntity single(String sql, MapSqlParameterSource p) {
        List<RelatedEntity> list = jdbc.query(
                sql,
                p,
                (rs, i) -> new RelatedEntity(
                        rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4)));
        return list.isEmpty() ? null : list.getFirst();
    }

    /** Human-readable counterparty per transaction, resolved for one page in a few batched queries. */
    private Map<UUID, String> counterparties(List<Row> rows, UUID viewer) {
        Map<UUID, String> out = new HashMap<>();
        Set<UUID> transferIds = new HashSet<>();
        Set<UUID> paymentIds = new HashSet<>();
        Set<UUID> refundIds = new HashSet<>();
        for (Row r : rows) {
            if (r.relatedEntityId() == null) {
                continue;
            }
            switch (LedgerTransactionType.valueOf(r.type())) {
                case TRANSFER -> transferIds.add(r.relatedEntityId());
                case PAYMENT -> paymentIds.add(r.relatedEntityId());
                case REFUND -> refundIds.add(r.relatedEntityId());
                default -> {}
            }
        }
        Map<UUID, UUID[]> parties = new HashMap<>(); // related id -> {partyA, partyB}
        if (!transferIds.isEmpty()) {
            jdbc.query(
                    "SELECT id, sender_id, recipient_id FROM transfers WHERE id IN (:ids)",
                    new MapSqlParameterSource("ids", transferIds),
                    rs -> {
                        parties.put(
                                rs.getObject(1, UUID.class),
                                new UUID[] {rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)});
                    });
        }
        if (!paymentIds.isEmpty()) {
            jdbc.query(
                    "SELECT id, customer_id, merchant_id FROM payment_intents WHERE id IN (:ids)",
                    new MapSqlParameterSource("ids", paymentIds),
                    rs -> {
                        parties.put(
                                rs.getObject(1, UUID.class),
                                new UUID[] {rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)});
                    });
        }
        if (!refundIds.isEmpty()) {
            jdbc.query(
                    "SELECT r.id, p.customer_id, p.merchant_id FROM refunds r"
                            + " JOIN payment_intents p ON p.id = r.payment_intent_id WHERE r.id IN (:ids)",
                    new MapSqlParameterSource("ids", refundIds),
                    rs -> {
                        parties.put(
                                rs.getObject(1, UUID.class),
                                new UUID[] {rs.getObject(2, UUID.class), rs.getObject(3, UUID.class)});
                    });
        }
        List<UUID> userIds = new ArrayList<>();
        parties.values().forEach(p -> userIds.addAll(List.of(p)));
        Map<UUID, UserDirectory.Party> names = directory.lookup(userIds);
        for (Row r : rows) {
            String label = switch (LedgerTransactionType.valueOf(r.type())) {
                case TOP_UP -> "Sandbox funding";
                case PAYOUT -> "Bank account (sandbox)";
                case REVERSAL -> "LedgerForge operations";
                default -> {
                    UUID[] p = parties.get(r.relatedEntityId());
                    if (p == null) {
                        yield null;
                    }
                    UUID other = viewer.equals(p[0]) ? p[1] : p[0];
                    UserDirectory.Party party = names.get(other);
                    yield party == null
                            ? null
                            : party.role() == dev.ledgerforge.user.Role.MERCHANT
                                    ? party.displayName()
                                    : UserDirectory.shortName(party.displayName());
                }
            };
            out.put(r.id(), label);
        }
        return out;
    }

    private static LedgerTransactionType parseType(String type) {
        try {
            return LedgerTransactionType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown transaction type " + type);
        }
    }
}
