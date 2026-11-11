package dev.ledgerforge.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.ledgerforge.support.ApiClient;
import dev.ledgerforge.support.IntegrationTestBase;
import dev.ledgerforge.webhook.OutboxDispatcher;
import dev.ledgerforge.webhook.WebhookDeliveryWorker;
import dev.ledgerforge.webhook.WebhookSignature;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class WebhookIT extends IntegrationTestBase {

    @Autowired
    OutboxDispatcher dispatcher;

    @Autowired
    WebhookDeliveryWorker worker;

    /** A real HTTP receiver standing in for a merchant's server. */
    private HttpServer receiver;

    private final ConcurrentLinkedQueue<Received> received = new ConcurrentLinkedQueue<>();
    private final AtomicInteger respondWith = new AtomicInteger(200);

    record Received(String eventId, String eventType, String signature, String body) {}

    @BeforeEach
    void startReceiver() throws Exception {
        receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receiver.createContext("/hook", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(
                    exchange.getRequestHeaders().getFirst(WebhookSignature.EVENT_ID_HEADER),
                    exchange.getRequestHeaders().getFirst(WebhookSignature.EVENT_TYPE_HEADER),
                    exchange.getRequestHeaders().getFirst(WebhookSignature.SIGNATURE_HEADER),
                    body));
            byte[] resp = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(respondWith.get(), resp.length);
            exchange.getResponseBody().write(resp);
            exchange.close();
        });
        receiver.start();
    }

    @AfterEach
    void stopReceiver() {
        receiver.stop(0);
    }

    private String hookUrl() {
        return "http://127.0.0.1:" + receiver.getAddress().getPort() + "/hook";
    }

    private JsonNode register(ApiClient merchant, String url, List<String> events) {
        return merchant.post("/api/v1/merchant/webhooks", Map.of("url", url, "events", events), null)
                .expectStatus(201)
                .json();
    }

    private String pay(ApiClient customer, ApiClient merchant, long amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("merchantId", merchant.userId().toString());
        body.put("amountMinor", amount);
        body.put("currency", "INR");
        String id = customer.post("/api/v1/payment-intents", body, key())
                .expectStatus(201)
                .json()
                .get("id")
                .asString();
        customer.post("/api/v1/payment-intents/" + id + "/confirm", null, key()).expectStatus(200);
        return id;
    }

    /** Runs dispatcher and worker until the given delivery leaves the queue (or attempts run out). */
    private void pump(UUID deliveryId) {
        for (int i = 0; i < 20; i++) {
            drainOutbox();
            worker.deliverDueBatch();
            String status =
                    jdbc.queryForObject("SELECT status FROM webhook_deliveries WHERE id = ?", String.class, deliveryId);
            if (!"PENDING".equals(status)) {
                Integer leased = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM webhook_deliveries WHERE id = ? AND status = 'RETRYING'"
                                + " AND next_attempt_at > now() + interval '30 seconds'",
                        Integer.class,
                        deliveryId);
                if (leased == null || leased == 0) {
                    return;
                }
            }
        }
    }

    private void drainOutbox() {
        while (dispatcher.dispatchBatch() > 0) {
            // other tests leave pending events behind; publish everything
        }
    }

    private UUID deliveryFor(UUID endpointId) {
        drainOutbox();
        return jdbc.queryForObject(
                "SELECT id FROM webhook_deliveries WHERE endpoint_id = ? ORDER BY created_at DESC LIMIT 1",
                UUID.class,
                endpointId);
    }

    @Test
    void secretIsShownOnceAndUnsafeUrlsAreRejected() {
        ApiClient m = merchant("Hook Shop");
        JsonNode created = register(m, hookUrl(), List.of("payment.succeeded"));
        String secret = created.get("secret").asString();
        assertThat(secret).startsWith("whsec_");
        assertThat(created.get("webhook").get("secretHint").asString()).endsWith(secret.substring(secret.length() - 4));
        String list = m.get("/api/v1/merchant/webhooks").expectStatus(200).body();
        assertThat(list).doesNotContain(secret);
        // Stored encrypted, never in plaintext.
        assertThat(dbCount("SELECT COUNT(*) FROM webhook_endpoints WHERE secret_ciphertext LIKE ?", "%" + secret + "%"))
                .isZero();

        assertThat(m.post(
                                "/api/v1/merchant/webhooks",
                                Map.of("url", "file:///etc/passwd", "events", List.of("payment.succeeded")),
                                null)
                        .expectStatus(422)
                        .code())
                .isEqualTo("WEBHOOK_URL_REJECTED");
        m.post("/api/v1/merchant/webhooks", Map.of("url", hookUrl(), "events", List.of("bogus.event")), null)
                .expectStatus(400);

        // Isolation between merchants.
        ApiClient other = merchant("Other Hook Shop");
        String id = created.get("webhook").get("id").asString();
        other.get("/api/v1/merchant/webhooks/" + id + "/deliveries").expectStatus(404);
        other.patch("/api/v1/merchant/webhooks/" + id, Map.of("enabled", false)).expectStatus(404);
        other.delete("/api/v1/merchant/webhooks/" + id).expectStatus(404);
        customer("hook-customer").get("/api/v1/merchant/webhooks").expectStatus(403);
    }

    @Test
    void paymentEventIsDeliveredSignedAndDeduplicatedOnFanOut() {
        ApiClient m = merchant("Signed Shop");
        ApiClient c = customer("signed");
        topUp(c, 50_000);
        JsonNode created = register(m, hookUrl(), List.of("payment.succeeded"));
        String secret = created.get("secret").asString();
        UUID endpointId = UUID.fromString(created.get("webhook").get("id").asString());

        String paymentId = pay(c, m, 12_345);
        UUID deliveryId = deliveryFor(endpointId);
        drainOutbox(); // a second dispatch must not create a duplicate delivery
        jdbc.update("UPDATE outbox_events SET status = 'PENDING' WHERE aggregate_id = ?::uuid", paymentId);
        drainOutbox(); // re-publishing the same event is idempotent (unique event/endpoint)
        assertThat(dbCount("SELECT COUNT(*) FROM webhook_deliveries WHERE endpoint_id = ?", endpointId))
                .isEqualTo(1);
        pump(deliveryId);

        assertThat(received).hasSize(1);
        Received r = received.peek();
        assertThat(r.eventType()).isEqualTo("payment.succeeded");
        assertThat(WebhookSignature.verify(secret, r.signature(), r.body(), Instant.now(), Duration.ofMinutes(5)))
                .isEqualTo(WebhookSignature.Result.VALID);
        JsonNode payload =
                tools.jackson.databind.json.JsonMapper.builder().build().readTree(r.body());
        assertThat(payload.get("data").get("paymentId").asString()).isEqualTo(paymentId);
        assertThat(payload.get("data").get("amountMinor").asLong()).isEqualTo(12_345);
        assertThat(r.body()).doesNotContain("@"); // no customer email in merchant webhooks
        String outboxId = jdbc.queryForObject(
                "SELECT id::text FROM outbox_events WHERE aggregate_id = ?::uuid AND event_type = 'payment.succeeded'",
                String.class,
                paymentId);
        assertThat(r.eventId()).isEqualTo(outboxId);

        JsonNode delivery = m.get("/api/v1/merchant/webhooks/" + endpointId + "/deliveries/" + deliveryId)
                .expectStatus(200)
                .json();
        assertThat(delivery.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(delivery.get("attemptCount").asInt()).isEqualTo(1);
        assertThat(delivery.get("lastHttpStatus").asInt()).isEqualTo(200);
        assertThat(delivery.get("attempts").size()).isEqualTo(1);
        assertThat(delivery.get("payload").get("data").get("paymentId").asString())
                .isEqualTo(paymentId);
    }

    @Test
    void failedDeliveriesRetryWithBackoffThenDeadLetterAndCanBeReplayed() {
        ApiClient m = merchant("Flaky Shop");
        ApiClient c = customer("flaky");
        topUp(c, 50_000);
        JsonNode created = register(m, hookUrl(), List.of("payment.succeeded"));
        UUID endpointId = UUID.fromString(created.get("webhook").get("id").asString());
        respondWith.set(500);
        pay(c, m, 1_000);
        UUID deliveryId = deliveryFor(endpointId);

        worker.deliverDueBatch();
        Map<String, Object> afterFirst = jdbc.queryForMap(
                "SELECT status, attempt_count, last_http_status, next_attempt_at > now() AS future"
                        + " FROM webhook_deliveries WHERE id = ?",
                deliveryId);
        assertThat(afterFirst.get("status")).isEqualTo("RETRYING");
        assertThat(afterFirst.get("attempt_count")).isEqualTo(1);
        assertThat(afterFirst.get("last_http_status")).isEqualTo(500);
        assertThat(afterFirst.get("future")).isEqualTo(true);

        // Fast-forward the backoff schedule for the remaining attempts (max-attempts=3 in tests).
        for (int i = 0; i < 2; i++) {
            jdbc.update(
                    "UPDATE webhook_deliveries SET next_attempt_at = now() - interval '1 second' WHERE id = ?",
                    deliveryId);
            worker.deliverDueBatch();
        }
        JsonNode dead = m.get("/api/v1/merchant/webhooks/" + endpointId + "/deliveries/" + deliveryId)
                .json();
        assertThat(dead.get("status").asString()).isEqualTo("DEAD");
        assertThat(dead.get("attemptCount").asInt()).isEqualTo(3);
        assertThat(dead.get("attempts").size()).isEqualTo(3);
        assertThat(dead.has("nextAttemptAt")).isFalse();
        // Every attempt carried the same stable event id, so receivers can de-duplicate.
        assertThat(received.stream().map(Received::eventId).distinct()).hasSize(1);

        assertThat(m.get("/api/v1/merchant/webhooks/" + endpointId + "/deliveries?status=DEAD")
                        .json()
                        .get("totalItems")
                        .asLong())
                .isEqualTo(1);
        assertThat(m.get("/api/v1/notifications").json().toString()).contains("WEBHOOK_DEAD");

        respondWith.set(204);
        JsonNode replayed = m.post(
                        "/api/v1/merchant/webhooks/" + endpointId + "/replay",
                        Map.of("deliveryId", deliveryId.toString()),
                        null)
                .expectStatus(202)
                .json();
        assertThat(replayed.get("status").asString()).isEqualTo("PENDING");
        assertThat(replayed.get("replayCount").asInt()).isEqualTo(1);
        pump(deliveryId);
        assertThat(jdbc.queryForObject("SELECT status FROM webhook_deliveries WHERE id = ?", String.class, deliveryId))
                .isEqualTo("SUCCEEDED");
        // Already scheduled deliveries cannot be replayed; replays are bounded.
        m.post("/api/v1/merchant/webhooks/" + endpointId + "/replay", Map.of("deliveryId", deliveryId.toString()), null)
                .expectStatus(202);
        m.post("/api/v1/merchant/webhooks/" + endpointId + "/replay", Map.of("deliveryId", deliveryId.toString()), null)
                .expectStatus(409);
    }

    @Test
    void disabledAndDeletedEndpointsStopReceivingEvents() {
        ApiClient m = merchant("Quiet Shop");
        ApiClient c = customer("quiet");
        topUp(c, 50_000);
        JsonNode created = register(m, hookUrl(), List.of("payment.succeeded"));
        UUID endpointId = UUID.fromString(created.get("webhook").get("id").asString());
        m.patch("/api/v1/merchant/webhooks/" + endpointId, Map.of("enabled", false))
                .expectStatus(200);
        pay(c, m, 2_000);
        drainOutbox();
        assertThat(dbCount("SELECT COUNT(*) FROM webhook_deliveries WHERE endpoint_id = ?", endpointId))
                .isZero();

        m.patch("/api/v1/merchant/webhooks/" + endpointId, Map.of("enabled", true))
                .expectStatus(200);
        m.delete("/api/v1/merchant/webhooks/" + endpointId).expectStatus(204);
        pay(c, m, 3_000);
        drainOutbox();
        assertThat(dbCount("SELECT COUNT(*) FROM webhook_deliveries WHERE endpoint_id = ?", endpointId))
                .isZero();
        assertThat(m.get("/api/v1/merchant/webhooks").json().size()).isZero();
    }

    @Test
    void builtInSandboxReceiverVerifiesSignatures() {
        ApiClient m = merchant("Sandbox Receiver Shop");
        String sinkUrl = "http://localhost:" + port + "/api/v1/sandbox/webhook-sink/" + m.userId();
        JsonNode created = register(m, sinkUrl, List.of("payment.succeeded"));
        UUID endpointId = UUID.fromString(created.get("webhook").get("id").asString());
        JsonNode test = m.post("/api/v1/merchant/webhooks/" + endpointId + "/test", null, null)
                .expectStatus(202)
                .json();
        UUID deliveryId = UUID.fromString(test.get("id").asString());
        assertThat(test.get("eventType").asString()).isEqualTo("webhook.test");
        pump(deliveryId);
        assertThat(jdbc.queryForObject("SELECT status FROM webhook_deliveries WHERE id = ?", String.class, deliveryId))
                .isEqualTo("SUCCEEDED");
        JsonNode receipts = m.get("/api/v1/merchant/webhooks/sandbox-receipts")
                .expectStatus(200)
                .json();
        assertThat(receipts.get(0).get("signatureValid").asBoolean()).isTrue();
        assertThat(receipts.get(0).get("eventType").asString()).isEqualTo("webhook.test");

        // After rotating the secret, a forged/old-secret request is rejected by the receiver.
        m.post("/api/v1/merchant/webhooks/" + endpointId + "/rotate-secret", null, null)
                .expectStatus(200);
        String body = "{\"id\":\"evt_forged\"}";
        long ts = Instant.now().getEpochSecond();
        ApiClient.Response forged = client().send(
                        "POST",
                        "/api/v1/sandbox/webhook-sink/" + m.userId(),
                        body,
                        Map.of(
                                WebhookSignature.SIGNATURE_HEADER,
                                WebhookSignature.sign(created.get("secret").asString(), ts, body)),
                        false);
        forged.expectStatus(400);
    }
}
