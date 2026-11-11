package dev.ledgerforge.webhook;

import dev.ledgerforge.common.config.AppProperties;
import dev.ledgerforge.common.error.ApiException;
import dev.ledgerforge.common.error.ErrorCode;
import io.swagger.v3.oas.annotations.Hidden;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A built-in webhook receiver for local demos (sandbox only). It verifies the signature exactly as
 * a merchant's server should, using {@link WebhookSignature#verify}, records the receipt, and can
 * be told to fail ({@code ?fail=true}) to demonstrate retries and dead-lettering.
 */
@Hidden
@RestController
public class SandboxWebhookReceiverController {

    private final AppProperties props;
    private final WebhookService webhooks;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SandboxWebhookReceiverController(
            AppProperties props, WebhookService webhooks, JdbcTemplate jdbc, Clock clock) {
        this.props = props;
        this.webhooks = webhooks;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @PostMapping("/api/v1/sandbox/webhook-sink/{merchantId}")
    public ResponseEntity<Map<String, Object>> receive(
            @PathVariable UUID merchantId,
            @RequestHeader(value = WebhookSignature.SIGNATURE_HEADER, required = false) String signature,
            @RequestHeader(value = WebhookSignature.EVENT_ID_HEADER, required = false) String eventId,
            @RequestHeader(value = WebhookSignature.EVENT_TYPE_HEADER, required = false) String eventType,
            @RequestParam(value = "fail", defaultValue = "false") boolean fail,
            @RequestBody(required = false) byte[] body) {
        if (!props.sandbox().enabled()) {
            throw new ApiException(ErrorCode.SANDBOX_DISABLED, "The sandbox receiver is disabled");
        }
        String payload = body == null ? "" : new String(body, StandardCharsets.UTF_8);
        boolean valid = webhooks.activeSecrets(merchantId).stream()
                .anyMatch(secret -> WebhookSignature.verify(
                                secret,
                                signature,
                                payload,
                                clock.instant(),
                                props.webhooks().signatureTolerance())
                        == WebhookSignature.Result.VALID);
        jdbc.update(
                "INSERT INTO sandbox_webhook_receipts (id, merchant_id, event_id, event_type, signature_valid, received_at)"
                        + " SELECT ?, ?, ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM users WHERE id = ?)",
                UUID.randomUUID(),
                merchantId,
                truncate(eventId),
                truncate(eventType),
                valid,
                Timestamp.from(clock.instant()),
                merchantId);
        if (fail) {
            return ResponseEntity.status(503).body(Map.of("received", true, "simulatedFailure", true));
        }
        if (!valid) {
            return ResponseEntity.status(400).body(Map.of("received", true, "signatureValid", false));
        }
        return ResponseEntity.ok(Map.of("received", true, "signatureValid", true));
    }

    private static String truncate(String s) {
        return s == null ? null : (s.length() > 64 ? s.substring(0, 64) : s);
    }
}
