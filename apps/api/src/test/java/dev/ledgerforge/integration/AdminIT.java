package dev.ledgerforge.integration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerforge.support.ApiClient;
import dev.ledgerforge.support.IntegrationTestBase;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class AdminIT extends IntegrationTestBase {

    @Test
    void adminEndpointsAreRestrictedToAdministrators() {
        ApiClient c = customer("not-admin");
        ApiClient m = merchant("Not Admin Shop");
        for (ApiClient nonAdmin : new ApiClient[] {c, m}) {
            nonAdmin.get("/api/v1/admin/reconciliation").expectStatus(403);
            nonAdmin.get("/api/v1/admin/audit-logs").expectStatus(403);
            nonAdmin.get("/actuator/metrics").expectStatus(403);
        }
        client().get("/api/v1/admin/overview").expectStatus(401);
        client().get("/actuator/health").expectStatus(200);
        client().get("/actuator/health/readiness").expectStatus(200);
        client().get("/actuator/health/liveness").expectStatus(200);
        admin().get("/actuator/metrics").expectStatus(200);
    }

    @Test
    void reconciliationOverviewAndAuditLog() {
        ApiClient c = customer("audited");
        topUp(c, 10_000);
        ApiClient admin = admin();
        JsonNode report =
                admin.get("/api/v1/admin/reconciliation").expectStatus(200).json();
        assertThat(report.get("healthy").asBoolean()).isTrue();
        assertThat(report.get("trialBalances")
                        .get(0)
                        .get("accountingEquationHolds")
                        .asBoolean())
                .isTrue();

        JsonNode overview =
                admin.get("/api/v1/admin/overview").expectStatus(200).json();
        assertThat(overview.get("systemAccounts").size()).isEqualTo(4);
        assertThat(overview.get("ledgerTransactions").asLong()).isPositive();

        JsonNode audit = admin.get("/api/v1/admin/audit-logs?actorId=" + c.userId())
                .expectStatus(200)
                .json();
        assertThat(audit.toString()).contains("WALLET_TOP_UP").contains("USER_REGISTERED");
        assertThat(audit.toString()).doesNotContain(PASSWORD);
        assertThat(admin.get("/api/v1/admin/users?q=audited")
                        .json()
                        .get("totalItems")
                        .asLong())
                .isEqualTo(1);
    }

    @Test
    void correctionsArePostedAsReversalsNeverEdits() {
        ApiClient c = customer("corrected");
        String topUpId = c.post(
                        "/api/v1/wallet/sandbox/top-ups", Map.of("amountMinor", 7_500, "currency", "INR"), key())
                .expectStatus(201)
                .json()
                .get("transactionId")
                .asString();
        ApiClient admin = admin();
        JsonNode reversal = admin.post(
                        "/api/v1/admin/ledger/transactions/" + topUpId + "/reversal",
                        Map.of("reason", "Duplicate sandbox funding"),
                        key())
                .expectStatus(201)
                .json();
        assertThat(walletBalance(c)).isZero();
        assertThat(reversal.get("reversesTransactionId").asString()).isEqualTo(topUpId);
        // The original entries are untouched; a second reversal would exceed the original.
        assertThat(dbCount("SELECT COUNT(*) FROM ledger_entries WHERE ledger_transaction_id = ?::uuid", topUpId))
                .isEqualTo(2);
        assertThat(admin.post(
                                "/api/v1/admin/ledger/transactions/" + topUpId + "/reversal",
                                Map.of("reason", "again"),
                                key())
                        .expectStatus(422)
                        .code())
                .isEqualTo("LEDGER_INVARIANT_VIOLATION");
        JsonNode detail =
                c.get("/api/v1/transactions/" + topUpId).expectStatus(200).json();
        assertThat(detail.get("reversedBy").size()).isEqualTo(1);

        // Payments are corrected through refunds, not raw reversals.
        ApiClient m = merchant("Reversal Shop");
        topUp(c, 5_000);
        Map<String, Object> body = new HashMap<>();
        body.put("merchantId", m.userId().toString());
        body.put("amountMinor", 5_000);
        body.put("currency", "INR");
        String pid =
                c.post("/api/v1/payment-intents", body, key()).json().get("id").asString();
        String ledgerTx = c.post("/api/v1/payment-intents/" + pid + "/confirm", null, key())
                .json()
                .get("ledgerTransactionId")
                .asString();
        admin.post("/api/v1/admin/ledger/transactions/" + ledgerTx + "/reversal", Map.of("reason", "no"), key())
                .expectStatus(409);
    }

    @Test
    void apiDocumentationIsGenerated() {
        ApiClient.Response docs = client().get("/v3/api-docs");
        docs.expectStatus(200);
        JsonNode json = docs.json();
        assertThat(json.get("paths").has("/api/v1/transfers")).isTrue();
        assertThat(json.get("paths").has("/api/v1/payment-intents/{paymentId}/confirm"))
                .isTrue();
        assertThat(json.get("components").get("securitySchemes").has("bearer")).isTrue();
    }
}
