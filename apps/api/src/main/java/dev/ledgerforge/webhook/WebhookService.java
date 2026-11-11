package dev.ledgerforge.webhook;

import dev.ledgerforge.audit.AuditLog;
import dev.ledgerforge.audit.AuditService;
import dev.ledgerforge.common.config.AppProperties;
import dev.ledgerforge.common.error.ApiException;
import dev.ledgerforge.common.error.ErrorCode;
import dev.ledgerforge.common.security.AuthenticatedUser;
import dev.ledgerforge.common.web.PageParams;
import dev.ledgerforge.common.web.PageResponse;
import dev.ledgerforge.webhook.WebhookDtos.AttemptView;
import dev.ledgerforge.webhook.WebhookDtos.DeliveryView;
import dev.ledgerforge.webhook.WebhookDtos.WebhookView;
import dev.ledgerforge.webhook.WebhookDtos.WebhookWithSecret;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Merchant webhook endpoint management, delivery inspection and manual replay. */
@Service
public class WebhookService {

    private static final int MAX_ENDPOINTS = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final WebhookEndpointRepository endpoints;
    private final WebhookUrlValidator urlValidator;
    private final SecretCipher cipher;
    private final OutboxService outbox;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AppProperties props;
    private final Clock clock;

    public WebhookService(
            WebhookEndpointRepository endpoints,
            WebhookUrlValidator urlValidator,
            SecretCipher cipher,
            OutboxService outbox,
            AuditService audit,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            AppProperties props,
            Clock clock) {
        this.endpoints = endpoints;
        this.urlValidator = urlValidator;
        this.cipher = cipher;
        this.outbox = outbox;
        this.audit = audit;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public WebhookWithSecret create(AuthenticatedUser merchant, WebhookDtos.CreateWebhookRequest request) {
        if (endpoints.countByMerchantIdAndDeletedAtIsNull(merchant.id()) >= MAX_ENDPOINTS) {
            throw new ApiException(
                    ErrorCode.LIMIT_EXCEEDED,
                    "A merchant can register at most " + MAX_ENDPOINTS + " webhook endpoints");
        }
        String url = urlValidator.validate(request.url()).toString();
        List<String> events = validateEvents(request.events());
        String secret = newSecret();
        WebhookEndpoint endpoint = endpoints.save(new WebhookEndpoint(
                UUID.randomUUID(),
                merchant.id(),
                url,
                blankToNull(request.description()),
                cipher.encrypt(secret),
                hint(secret),
                events,
                clock.instant()));
        audit.record(
                merchant,
                "WEBHOOK_CREATED",
                "WEBHOOK",
                endpoint.getId(),
                AuditLog.Result.SUCCESS,
                Map.of("events", events));
        return new WebhookWithSecret(
                view(endpoint, stats(List.of(endpoint.getId())).get(endpoint.getId())), secret);
    }

    @Transactional(readOnly = true)
    public List<WebhookView> list(AuthenticatedUser merchant) {
        List<WebhookEndpoint> all = endpoints.findByMerchantIdAndDeletedAtIsNullOrderByCreatedAtDesc(merchant.id());
        Map<UUID, WebhookDtos.DeliveryStats> stats =
                stats(all.stream().map(WebhookEndpoint::getId).toList());
        return all.stream().map(e -> view(e, stats.get(e.getId()))).toList();
    }

    @Transactional
    public WebhookView update(AuthenticatedUser merchant, UUID id, WebhookDtos.UpdateWebhookRequest request) {
        WebhookEndpoint endpoint = owned(merchant, id);
        String url = request.url() == null
                ? null
                : urlValidator.validate(request.url()).toString();
        List<String> events = request.events() == null ? null : validateEvents(request.events());
        endpoint.update(url, request.description(), events, request.enabled(), clock.instant());
        audit.record(merchant, "WEBHOOK_UPDATED", "WEBHOOK", id, AuditLog.Result.SUCCESS, Map.of());
        return view(endpoint, stats(List.of(id)).get(id));
    }

    @Transactional
    public void delete(AuthenticatedUser merchant, UUID id) {
        WebhookEndpoint endpoint = owned(merchant, id);
        endpoint.softDelete(clock.instant());
        audit.record(merchant, "WEBHOOK_DELETED", "WEBHOOK", id, AuditLog.Result.SUCCESS, Map.of());
    }

    @Transactional
    public WebhookWithSecret rotateSecret(AuthenticatedUser merchant, UUID id) {
        WebhookEndpoint endpoint = owned(merchant, id);
        String secret = newSecret();
        endpoint.rotateSecret(cipher.encrypt(secret), hint(secret), clock.instant());
        audit.record(merchant, "WEBHOOK_SECRET_ROTATED", "WEBHOOK", id, AuditLog.Result.SUCCESS, Map.of());
        return new WebhookWithSecret(view(endpoint, stats(List.of(id)).get(id)), secret);
    }

    /** Sends a {@code webhook.test} event to this endpoint only. */
    @Transactional
    public DeliveryView sendTest(AuthenticatedUser merchant, UUID id) {
        WebhookEndpoint endpoint = owned(merchant, id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", "This is a test event from LedgerForge");
        data.put("webhookId", endpoint.getId().toString());
        // merchantId null: this event is delivered to this endpoint only, never fanned out.
        OutboxEvent event = outbox.record(EventTypes.WEBHOOK_TEST, "WEBHOOK", endpoint.getId(), null, data);
        UUID deliveryId = UUID.randomUUID();
        Instant now = clock.instant();
        // The outbox row is flushed first so the delivery's foreign key is satisfied.
        endpoints.flush();
        jdbc.update("""
                INSERT INTO webhook_deliveries (id, event_id, endpoint_id, status, attempt_count, replay_count,
                                                next_attempt_at, created_at)
                VALUES (?, ?, ?, 'PENDING', 0, 0, ?, ?)
                """, deliveryId, event.getId(), endpoint.getId(), Timestamp.from(now), Timestamp.from(now));
        audit.record(merchant, "WEBHOOK_TEST_SENT", "WEBHOOK", id, AuditLog.Result.SUCCESS, Map.of());
        return delivery(merchant, id, deliveryId);
    }

    @Transactional(readOnly = true)
    public PageResponse<DeliveryView> deliveries(AuthenticatedUser merchant, UUID id, String status, PageParams page) {
        owned(merchant, id);
        String statusFilter =
                status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (statusFilter != null
                && !List.of("PENDING", "RETRYING", "SUCCEEDED", "DEAD").contains(statusFilter)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown delivery status " + status);
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM webhook_deliveries WHERE endpoint_id = ? AND (?::text IS NULL OR status = ?)",
                Long.class,
                id,
                statusFilter,
                statusFilter);
        List<DeliveryView> items = jdbc.query(
                DELIVERY_SELECT + " WHERE d.endpoint_id = ? AND (?::text IS NULL OR d.status = ?)"
                        + " ORDER BY d.created_at DESC LIMIT ? OFFSET ?",
                (rs, i) -> mapDelivery(rs, List.of()),
                id,
                statusFilter,
                statusFilter,
                page.size(),
                page.offset());
        return PageResponse.of(items, page.page(), page.size(), total == null ? 0 : total);
    }

    @Transactional(readOnly = true)
    public DeliveryView delivery(AuthenticatedUser merchant, UUID id, UUID deliveryId) {
        owned(merchant, id);
        List<AttemptView> attempts = jdbc.query(
                """
                SELECT attempt_number, http_status, error, duration_ms, response_snippet, attempted_at
                  FROM webhook_delivery_attempts WHERE delivery_id = ? ORDER BY attempt_number DESC, attempted_at DESC
                """,
                (rs, i) -> new AttemptView(
                        rs.getInt(1),
                        (Integer) rs.getObject(2),
                        rs.getString(3),
                        rs.getLong(4),
                        rs.getString(5),
                        rs.getTimestamp(6).toInstant()),
                deliveryId);
        List<DeliveryView> rows = jdbc.query(
                DELIVERY_SELECT + " WHERE d.endpoint_id = ? AND d.id = ?",
                (rs, i) -> mapDelivery(rs, attempts),
                id,
                deliveryId);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Delivery");
        }
        return rows.getFirst();
    }

    /**
     * Manual replay: re-queues a delivery with a fresh attempt budget. Bounded by {@code
     * max-manual-replays} per delivery so replays cannot create unbounded retry loops.
     */
    @Transactional
    public DeliveryView replay(AuthenticatedUser merchant, UUID id, UUID deliveryId) {
        WebhookEndpoint endpoint = owned(merchant, id);
        if (!endpoint.isEnabled()) {
            throw ApiException.invalidState("Enable the endpoint before replaying deliveries");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, replay_count FROM webhook_deliveries WHERE id = ? AND endpoint_id = ? FOR UPDATE",
                deliveryId,
                id);
        if (rows.isEmpty()) {
            throw ApiException.notFound("Delivery");
        }
        String status = (String) rows.getFirst().get("status");
        int replays = ((Number) rows.getFirst().get("replay_count")).intValue();
        if (status.equals("PENDING") || status.equals("RETRYING")) {
            throw ApiException.invalidState("Delivery is already scheduled");
        }
        if (replays >= props.webhooks().maxManualReplays()) {
            throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "This delivery has reached the manual replay limit");
        }
        jdbc.update("""
                UPDATE webhook_deliveries
                   SET status = 'PENDING', attempt_count = 0, replay_count = replay_count + 1, next_attempt_at = ?
                 WHERE id = ?
                """, Timestamp.from(clock.instant()), deliveryId);
        audit.record(
                merchant,
                "WEBHOOK_REPLAYED",
                "WEBHOOK_DELIVERY",
                deliveryId,
                AuditLog.Result.SUCCESS,
                Map.of("previousStatus", status));
        return delivery(merchant, id, deliveryId);
    }

    @Transactional(readOnly = true)
    public List<WebhookDtos.ReceiptView> sandboxReceipts(AuthenticatedUser merchant) {
        return jdbc.query(
                """
                SELECT id, event_id, event_type, signature_valid, received_at FROM sandbox_webhook_receipts
                 WHERE merchant_id = ? ORDER BY received_at DESC LIMIT 25
                """,
                (rs, i) -> new WebhookDtos.ReceiptView(
                        rs.getObject(1, UUID.class),
                        rs.getString(2),
                        rs.getString(3),
                        (Boolean) rs.getObject(4),
                        rs.getTimestamp(5).toInstant()),
                merchant.id());
    }

    /** Secrets of a merchant's active endpoints (used only by the sandbox receiver to verify). */
    @Transactional(readOnly = true)
    public List<String> activeSecrets(UUID merchantId) {
        return endpoints.findByMerchantIdAndDeletedAtIsNullOrderByCreatedAtDesc(merchantId).stream()
                .map(e -> cipher.decrypt(e.getSecretCiphertext()))
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    private static final String DELIVERY_SELECT = """
            SELECT d.id, d.event_id, e.event_type, d.status, d.attempt_count, d.replay_count, d.next_attempt_at,
                   d.last_attempt_at, d.last_http_status, d.last_error, d.delivered_at, d.created_at, e.payload::text
              FROM webhook_deliveries d JOIN outbox_events e ON e.id = d.event_id
            """;

    private DeliveryView mapDelivery(java.sql.ResultSet rs, List<AttemptView> attempts) throws java.sql.SQLException {
        return new DeliveryView(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getString(3),
                rs.getString(4),
                rs.getInt(5),
                rs.getInt(6),
                instant(rs.getTimestamp(7)),
                instant(rs.getTimestamp(8)),
                (Integer) rs.getObject(9),
                rs.getString(10),
                instant(rs.getTimestamp(11)),
                instant(rs.getTimestamp(12)),
                objectMapper.readTree(rs.getString(13)),
                attempts);
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    private WebhookEndpoint owned(AuthenticatedUser merchant, UUID id) {
        return endpoints
                .findByIdAndMerchantIdAndDeletedAtIsNull(id, merchant.id())
                .orElseThrow(() -> ApiException.notFound("Webhook endpoint"));
    }

    private Map<UUID, WebhookDtos.DeliveryStats> stats(List<UUID> endpointIds) {
        Map<UUID, WebhookDtos.DeliveryStats> out = new HashMap<>();
        for (UUID id : endpointIds) {
            out.put(id, new WebhookDtos.DeliveryStats(0, 0, 0));
        }
        if (endpointIds.isEmpty()) {
            return out;
        }
        jdbc.query(
                """
                SELECT endpoint_id,
                       COUNT(*) FILTER (WHERE status = 'SUCCEEDED'),
                       COUNT(*) FILTER (WHERE status IN ('PENDING', 'RETRYING')),
                       COUNT(*) FILTER (WHERE status = 'DEAD')
                  FROM webhook_deliveries WHERE endpoint_id = ANY (?) GROUP BY endpoint_id
                """,
                rs -> {
                    out.put(
                            rs.getObject(1, UUID.class),
                            new WebhookDtos.DeliveryStats(rs.getLong(2), rs.getLong(3), rs.getLong(4)));
                },
                (Object) endpointIds.toArray(UUID[]::new));
        return out;
    }

    private static List<String> validateEvents(List<String> events) {
        List<String> out = new ArrayList<>();
        for (String e : events) {
            String type = e == null ? "" : e.trim();
            if (!EventTypes.SUBSCRIBABLE.contains(type)) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "Unknown event type '" + type + "'",
                        Map.of("allowed", EventTypes.SUBSCRIBABLE));
            }
            if (!out.contains(type)) {
                out.add(type);
            }
        }
        if (out.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Subscribe to at least one event");
        }
        return out;
    }

    private static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hint(String secret) {
        return "whsec_…" + secret.substring(secret.length() - 4);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static WebhookView view(WebhookEndpoint e, WebhookDtos.DeliveryStats stats) {
        return new WebhookView(
                e.getId(),
                e.getUrl(),
                e.getDescription(),
                e.getSubscribedEvents(),
                e.isEnabled(),
                e.getSecretHint(),
                stats == null ? new WebhookDtos.DeliveryStats(0, 0, 0) : stats,
                e.getCreatedAt(),
                e.getUpdatedAt());
    }
}
