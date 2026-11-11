package dev.ledgerforge.webhook;

import dev.ledgerforge.common.config.AppProperties;
import dev.ledgerforge.common.error.ApiException;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers webhook attempts with at-least-once semantics.
 *
 * <ol>
 *   <li><b>Claim</b> (short transaction): select due deliveries {@code FOR UPDATE SKIP LOCKED} and
 *       push their {@code next_attempt_at} forward by a lease, so no other worker picks them up.
 *   <li><b>Send</b> (no transaction held): re-validate the URL (DNS-rebinding defence), sign, POST
 *       with connect/request timeouts and redirects disabled.
 *   <li><b>Record</b> (short transaction): store the attempt; on 2xx mark SUCCEEDED, otherwise
 *       schedule a retry with exponential backoff, or DEAD after {@code max-attempts}.
 * </ol>
 *
 * If the process dies mid-send the lease expires and the delivery is retried; receivers
 * de-duplicate on the stable {@code LedgerForge-Event-Id}.
 */
@Component
public class WebhookDeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryWorker.class);
    private static final int SNIPPET = 512;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AppProperties.Webhooks config;
    private final WebhookUrlValidator urlValidator;
    private final SecretCipher cipher;
    private final Clock clock;
    private final HttpClient http;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public WebhookDeliveryWorker(
            JdbcTemplate jdbc,
            PlatformTransactionManager txManager,
            AppProperties props,
            WebhookUrlValidator urlValidator,
            SecretCipher cipher,
            Clock clock) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.config = props.webhooks();
        this.urlValidator = urlValidator;
        this.cipher = cipher;
        this.clock = clock;
        this.http = HttpClient.newBuilder()
                .connectTimeout(config.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    @Scheduled(fixedDelayString = "${app.webhooks.poll-interval-ms:1000}", initialDelay = 2000)
    public void scheduled() {
        if (config.workersEnabled()) {
            deliverDueBatch();
        }
    }

    record Claimed(
            UUID deliveryId,
            int attemptCount,
            UUID eventId,
            String eventType,
            String payload,
            String url,
            String secretCiphertext,
            boolean endpointActive) {}

    /** Claims and processes one batch of due deliveries; returns how many were attempted. */
    public int deliverDueBatch() {
        List<Claimed> claimed = claim();
        List<Future<?>> futures = new ArrayList<>();
        for (Claimed c : claimed) {
            futures.add(executor.submit(() -> attempt(c)));
        }
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                log.error("Webhook delivery task failed", e);
            }
        }
        return claimed.size();
    }

    private List<Claimed> claim() {
        List<Claimed> result = tx.execute(status -> {
            Instant now = clock.instant();
            List<Claimed> rows = jdbc.query(
                    """
                    SELECT d.id, d.attempt_count, e.id, e.event_type, e.payload::text, w.url, w.secret_ciphertext,
                           (w.enabled AND w.deleted_at IS NULL)
                      FROM webhook_deliveries d
                      JOIN outbox_events e ON e.id = d.event_id
                      JOIN webhook_endpoints w ON w.id = d.endpoint_id
                     WHERE d.status IN ('PENDING', 'RETRYING') AND d.next_attempt_at <= ?
                     ORDER BY d.next_attempt_at
                     LIMIT ?
                     FOR UPDATE OF d SKIP LOCKED
                    """,
                    (rs, i) -> new Claimed(
                            rs.getObject(1, UUID.class),
                            rs.getInt(2),
                            rs.getObject(3, UUID.class),
                            rs.getString(4),
                            rs.getString(5),
                            rs.getString(6),
                            rs.getString(7),
                            rs.getBoolean(8)),
                    Timestamp.from(now),
                    config.batchSize());
            Timestamp leaseUntil = Timestamp.from(now.plus(config.leaseDuration()));
            for (Claimed c : rows) {
                jdbc.update(
                        "UPDATE webhook_deliveries SET next_attempt_at = ? WHERE id = ?", leaseUntil, c.deliveryId());
            }
            return rows;
        });
        return result == null ? List.of() : result;
    }

    private void attempt(Claimed c) {
        int attemptNumber = c.attemptCount() + 1;
        long started = System.nanoTime();
        Integer httpStatus = null;
        String error = null;
        String snippet = null;
        if (!c.endpointActive()) {
            recordOutcome(c, attemptNumber, null, "Endpoint disabled or deleted", null, 0, true);
            return;
        }
        try {
            URI uri = urlValidator.validate(c.url());
            String secret = cipher.decrypt(c.secretCiphertext());
            long ts = clock.instant().getEpochSecond();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(config.requestTimeout())
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "LedgerForge-Webhooks/1.0")
                    .header(WebhookSignature.EVENT_ID_HEADER, c.eventId().toString())
                    .header(WebhookSignature.EVENT_TYPE_HEADER, c.eventType())
                    .header(WebhookSignature.TIMESTAMP_HEADER, Long.toString(ts))
                    .header(WebhookSignature.SIGNATURE_HEADER, WebhookSignature.sign(secret, ts, c.payload()))
                    .POST(HttpRequest.BodyPublishers.ofString(c.payload(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            httpStatus = response.statusCode();
            try (InputStream body = response.body()) {
                snippet = new String(body.readNBytes(SNIPPET), StandardCharsets.UTF_8);
            }
            if (httpStatus >= 300 && httpStatus < 400) {
                error = "Redirects are not followed (HTTP " + httpStatus + ")";
            } else if (httpStatus < 200 || httpStatus >= 300) {
                error = "Non-2xx response (HTTP " + httpStatus + ")";
            }
        } catch (ApiException e) {
            error = "URL rejected: " + e.getMessage();
        } catch (java.net.http.HttpTimeoutException e) {
            error = "Timed out after " + config.requestTimeout().toSeconds() + "s";
        } catch (IOException e) {
            error = "Connection failed: " + e.getClass().getSimpleName();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error = "Interrupted";
        } catch (RuntimeException e) {
            log.error("Unexpected webhook delivery error", e);
            error = "Internal delivery error";
        }
        long durationMs = (System.nanoTime() - started) / 1_000_000;
        recordOutcome(c, attemptNumber, httpStatus, error, snippet, durationMs, false);
    }

    private void recordOutcome(
            Claimed c,
            int attemptNumber,
            Integer httpStatus,
            String error,
            String snippet,
            long durationMs,
            boolean forceDead) {
        Instant now = clock.instant();
        boolean success = error == null;
        String status;
        Instant next = null;
        if (success) {
            status = "SUCCEEDED";
        } else if (forceDead || attemptNumber >= config.maxAttempts()) {
            status = "DEAD";
        } else {
            status = "RETRYING";
            next = now.plus(backoff(attemptNumber));
        }
        String finalStatus = status;
        Instant finalNext = next;
        tx.executeWithoutResult(s -> {
            jdbc.update(
                    """
                    INSERT INTO webhook_delivery_attempts
                        (id, delivery_id, attempt_number, http_status, error, duration_ms, response_snippet, attempted_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    c.deliveryId(),
                    attemptNumber,
                    httpStatus,
                    truncate(error, 500),
                    durationMs,
                    truncate(snippet, SNIPPET),
                    Timestamp.from(now));
            jdbc.update(
                    """
                    UPDATE webhook_deliveries
                       SET status = ?, attempt_count = ?, last_attempt_at = ?, last_http_status = ?,
                           last_error = ?, next_attempt_at = ?, delivered_at = CASE WHEN ? THEN ? ELSE delivered_at END
                     WHERE id = ?
                    """,
                    finalStatus,
                    attemptNumber,
                    Timestamp.from(now),
                    httpStatus,
                    truncate(error, 500),
                    finalNext == null ? null : Timestamp.from(finalNext),
                    success,
                    Timestamp.from(now),
                    c.deliveryId());
        });
        if (!success) {
            log.info(
                    "Webhook delivery {} attempt {} failed: {} -> {}",
                    c.deliveryId(),
                    attemptNumber,
                    error,
                    finalStatus);
        }
    }

    /** base * 2^(attempt-1), capped, with up to 10% jitter to avoid synchronized retry storms. */
    Duration backoff(int attemptNumber) {
        long base = config.baseBackoff().toMillis();
        long exp = base * (1L << Math.min(attemptNumber - 1, 20));
        long capped = Math.min(exp, config.maxBackoff().toMillis());
        long jitter = ThreadLocalRandom.current().nextLong(capped / 10 + 1);
        return Duration.ofMillis(capped + jitter);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
