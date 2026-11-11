package dev.ledgerforge.notification;

import dev.ledgerforge.common.error.ApiException;
import dev.ledgerforge.common.security.AuthenticatedUser;
import dev.ledgerforge.user.User;
import dev.ledgerforge.user.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Activity notifications derived from real records: money received into the caller's accounts
 * and (for merchants) webhook deliveries that were dead-lettered. Read state is a single
 * "seen up to" timestamp on the user.
 */
@RestController
@Tag(name = "Notifications")
@SecurityRequirement(name = "bearer")
public class NotificationController {

    private static final Duration WINDOW = Duration.ofDays(14);

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final Clock clock;

    public NotificationController(JdbcTemplate jdbc, UserRepository users, Clock clock) {
        this.jdbc = jdbc;
        this.users = users;
        this.clock = clock;
    }

    public record Notification(
            String id, String kind, String title, String body, String link, Instant createdAt, boolean unread) {}

    public record Notifications(long unreadCount, List<Notification> items) {}

    @GetMapping("/api/v1/notifications")
    @Operation(summary = "Recent activity notifications")
    @Transactional(readOnly = true)
    public Notifications list(@AuthenticationPrincipal AuthenticatedUser principal) {
        User user = users.findById(principal.id()).orElseThrow(() -> ApiException.notFound("User"));
        Instant since = clock.instant().minus(WINDOW);
        Instant seen = user.getNotificationsSeenAt() == null ? Instant.EPOCH : user.getNotificationsSeenAt();
        List<Notification> items = new ArrayList<>(jdbc.query(
                """
                SELECT t.id, t.transaction_type, t.reference_number, e.amount_minor, t.currency, t.created_at
                  FROM ledger_entries e
                  JOIN ledger_accounts a ON a.id = e.ledger_account_id
                  JOIN ledger_transactions t ON t.id = e.ledger_transaction_id
                 WHERE a.owner_id = ? AND e.entry_type = a.normal_balance
                   AND t.transaction_type IN ('TRANSFER', 'PAYMENT', 'REFUND') AND t.created_at >= ?
                   AND (t.initiated_by IS NULL OR t.initiated_by <> ?)
                 ORDER BY t.created_at DESC LIMIT 20
                """,
                (rs, i) -> {
                    Instant at = rs.getTimestamp(6).toInstant();
                    String type = rs.getString(2);
                    String title = switch (type) {
                        case "TRANSFER" -> "Money received";
                        case "PAYMENT" -> "Payment received";
                        default -> "Refund received";
                    };
                    return new Notification(
                            rs.getString(1),
                            type,
                            title,
                            rs.getString(5) + ":" + rs.getLong(4) + ":" + rs.getString(3),
                            "/transactions/" + rs.getString(1),
                            at,
                            at.isAfter(seen));
                },
                principal.id(),
                Timestamp.from(since),
                principal.id()));
        if (principal.isMerchant()) {
            items.addAll(jdbc.query(
                    """
                    SELECT d.id, e.event_type, d.last_attempt_at, w.id
                      FROM webhook_deliveries d
                      JOIN webhook_endpoints w ON w.id = d.endpoint_id
                      JOIN outbox_events e ON e.id = d.event_id
                     WHERE w.merchant_id = ? AND d.status = 'DEAD' AND d.last_attempt_at >= ?
                     ORDER BY d.last_attempt_at DESC LIMIT 10
                    """,
                    (rs, i) -> {
                        Instant at = rs.getTimestamp(3).toInstant();
                        return new Notification(
                                rs.getString(1),
                                "WEBHOOK_DEAD",
                                "Webhook delivery failed",
                                rs.getString(2),
                                "/developers?webhook=" + rs.getObject(4, UUID.class),
                                at,
                                at.isAfter(seen));
                    },
                    principal.id(),
                    Timestamp.from(since)));
        }
        items.sort(Comparator.comparing(Notification::createdAt).reversed());
        List<Notification> top = items.stream().limit(20).toList();
        return new Notifications(top.stream().filter(Notification::unread).count(), top);
    }

    @PostMapping("/api/v1/notifications/read")
    @Operation(summary = "Mark all notifications as read")
    @Transactional
    public ResponseEntity<Void> markRead(@AuthenticationPrincipal AuthenticatedUser principal) {
        users.findById(principal.id()).ifPresent(u -> u.markNotificationsSeen(clock.instant()));
        return ResponseEntity.noContent().build();
    }
}
