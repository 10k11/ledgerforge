package dev.ledgerforge.integration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerforge.support.ApiClient;
import dev.ledgerforge.support.IntegrationTestBase;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class WalletTransferIT extends IntegrationTestBase {

    @Test
    void sandboxTopUpPostsBalancedLedgerTransaction() {
        ApiClient c = customer("topup");
        ApiClient.Response r =
                c.post("/api/v1/wallet/sandbox/top-ups", Map.of("amountMinor", 100_000, "currency", "INR"), key());
        r.expectStatus(201);
        assertThat(r.header("idempotent-replayed")).isEqualTo("false");
        JsonNode body = r.json();
        assertThat(body.get("balanceAfterMinor").asLong()).isEqualTo(100_000);
        assertThat(walletBalance(c)).isEqualTo(100_000);

        JsonNode ledger = c.get(
                        "/api/v1/transactions/" + body.get("transactionId").asString() + "/ledger")
                .expectStatus(200)
                .json();
        assertThat(ledger.get("balanced").asBoolean()).isTrue();
        assertThat(ledger.get("totalDebitsMinor").asLong()).isEqualTo(100_000);
        assertThat(ledger.get("totalCreditsMinor").asLong()).isEqualTo(100_000);
        assertThat(ledger.get("lines").get(0).get("entryType").asString()).isEqualTo("DEBIT");
        assertThat(ledger.get("lines").get(0).get("accountLabel").asString()).isEqualTo("Platform Funding Clearing");
        assertThat(ledger.get("lines").get(1).get("accountLabel").asString()).isEqualTo("Customer Wallet (you)");
        assertThat(dbCount(
                        "SELECT COUNT(*) FROM audit_logs WHERE actor_id = ? AND action = 'WALLET_TOP_UP'", c.userId()))
                .isEqualTo(1);
    }

    @Test
    void topUpValidation() {
        ApiClient c = customer("topupval");
        assertThat(c.post("/api/v1/wallet/sandbox/top-ups", Map.of("amountMinor", 100, "currency", "INR"), null)
                        .expectStatus(400)
                        .code())
                .isEqualTo("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(c.post("/api/v1/wallet/sandbox/top-ups", Map.of("amountMinor", 100, "currency", "INR"), "short")
                        .expectStatus(400)
                        .code())
                .isEqualTo("IDEMPOTENCY_KEY_INVALID");
        c.post("/api/v1/wallet/sandbox/top-ups", Map.of("amountMinor", 0, "currency", "INR"), key())
                .expectStatus(400);
        c.post("/api/v1/wallet/sandbox/top-ups", Map.of("amountMinor", -5, "currency", "INR"), key())
                .expectStatus(400);
        c.post("/api/v1/wallet/sandbox/top-ups", "{\"amountMinor\": 10.5, \"currency\": \"INR\"}", key())
                .expectStatus(400);
        assertThat(c.post("/api/v1/wallet/sandbox/top-ups", Map.of("amountMinor", 100, "currency", "USD"), key())
                        .expectStatus(422)
                        .code())
                .isEqualTo("CURRENCY_MISMATCH");
        assertThat(c.post("/api/v1/wallet/sandbox/top-ups", Map.of("amountMinor", 100, "currency", "XYZ"), key())
                        .expectStatus(422)
                        .code())
                .isEqualTo("UNSUPPORTED_CURRENCY");
        assertThat(c.post("/api/v1/wallet/sandbox/top-ups", Map.of("amountMinor", 10_000_001, "currency", "INR"), key())
                        .expectStatus(422)
                        .code())
                .isEqualTo("LIMIT_EXCEEDED");
        assertThat(walletBalance(c)).isZero();
    }

    @Test
    void merchantsCannotUseCustomerWalletEndpoints() {
        ApiClient m = merchant("Walletless Store");
        m.get("/api/v1/wallet").expectStatus(403);
        m.post("/api/v1/wallet/sandbox/top-ups", Map.of("amountMinor", 100, "currency", "INR"), key())
                .expectStatus(403);
        m.post("/api/v1/transfers", Map.of("recipient", "x@y.z", "amountMinor", 1, "currency", "INR"), key())
                .expectStatus(403);
    }

    @Test
    void transferMovesMoneyBetweenWallets() {
        ApiClient alice = customer("alice");
        ApiClient bob = customer("bob");
        topUp(alice, 50_000);
        String bobEmail = bob.get("/api/v1/auth/me").json().get("email").asString();

        JsonNode lookup = alice.post("/api/v1/transfers/recipient-lookup", Map.of("recipient", bobEmail), null)
                .expectStatus(200)
                .json();
        assertThat(lookup.get("displayName").asString()).isEqualTo("bob T.");
        assertThat(lookup.get("maskedEmail").asString()).contains("•••");

        JsonNode transfer = alice.post(
                        "/api/v1/transfers",
                        Map.of("recipient", bobEmail, "amountMinor", 20_000, "currency", "INR", "description", "Lunch"),
                        key())
                .expectStatus(201)
                .json();
        assertThat(transfer.get("direction").asString()).isEqualTo("SENT");
        assertThat(transfer.get("senderBalanceAfterMinor").asLong()).isEqualTo(30_000);
        assertThat(walletBalance(alice)).isEqualTo(30_000);
        assertThat(walletBalance(bob)).isEqualTo(20_000);

        // Recipient by account number works too.
        String aliceAccount =
                alice.get("/api/v1/wallet").json().get("accountNumber").asString();
        bob.post("/api/v1/transfers", Map.of("recipient", aliceAccount, "amountMinor", 5_000, "currency", "INR"), key())
                .expectStatus(201);
        assertThat(walletBalance(alice)).isEqualTo(35_000);

        JsonNode bobView = bob.get("/api/v1/transfers/" + transfer.get("id").asString())
                .expectStatus(200)
                .json();
        assertThat(bobView.get("direction").asString()).isEqualTo("RECEIVED");
        assertThat(bob.get("/api/v1/transfers?direction=RECEIVED")
                        .json()
                        .get("totalItems")
                        .asLong())
                .isEqualTo(1);

        // A third party cannot see the transfer or its ledger postings.
        ApiClient eve = customer("eve");
        eve.get("/api/v1/transfers/" + transfer.get("id").asString()).expectStatus(404);
        eve.get("/api/v1/transactions/" + transfer.get("ledgerTransactionId").asString())
                .expectStatus(404);
        eve.get("/api/v1/transactions/" + transfer.get("ledgerTransactionId").asString() + "/ledger")
                .expectStatus(404);
    }

    @Test
    void transferRules() {
        ApiClient alice = customer("rules");
        ApiClient bob = customer("rules-bob");
        topUp(alice, 1_000);
        String aliceEmail = alice.get("/api/v1/auth/me").json().get("email").asString();
        String bobEmail = bob.get("/api/v1/auth/me").json().get("email").asString();

        assertThat(alice.post(
                                "/api/v1/transfers",
                                Map.of("recipient", bobEmail, "amountMinor", 1_001, "currency", "INR"),
                                key())
                        .expectStatus(422)
                        .code())
                .isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(alice.post(
                                "/api/v1/transfers",
                                Map.of("recipient", aliceEmail, "amountMinor", 10, "currency", "INR"),
                                key())
                        .expectStatus(422)
                        .code())
                .isEqualTo("SELF_TRANSFER");
        assertThat(alice.post(
                                "/api/v1/transfers",
                                Map.of("recipient", uniqueEmail("ghost"), "amountMinor", 10, "currency", "INR"),
                                key())
                        .expectStatus(422)
                        .code())
                .isEqualTo("RECIPIENT_NOT_FOUND");
        assertThat(alice.post(
                                "/api/v1/transfers",
                                Map.of("recipient", bobEmail, "amountMinor", 10, "currency", "USD"),
                                key())
                        .expectStatus(422)
                        .code())
                .isEqualTo("CURRENCY_MISMATCH");
        // Merchants are not transfer recipients.
        ApiClient shop = merchant("Not A Wallet");
        String shopEmail = shop.get("/api/v1/auth/me").json().get("email").asString();
        alice.post("/api/v1/transfers", Map.of("recipient", shopEmail, "amountMinor", 10, "currency", "INR"), key())
                .expectStatus(422);
        assertThat(walletBalance(alice)).isEqualTo(1_000);
        assertThat(walletBalance(bob)).isZero();
    }

    @Test
    void repeatedIdenticalRequestsCreateOneTransfer() {
        ApiClient alice = customer("idem");
        ApiClient bob = customer("idem-bob");
        topUp(alice, 100_000);
        String bobEmail = bob.get("/api/v1/auth/me").json().get("email").asString();
        String k = key();
        Map<String, Object> body = Map.of("recipient", bobEmail, "amountMinor", 50_000, "currency", "INR");

        ApiClient.Response first = alice.post("/api/v1/transfers", body, k).expectStatus(201);
        for (int i = 0; i < 4; i++) {
            ApiClient.Response again = alice.post("/api/v1/transfers", body, k).expectStatus(201);
            assertThat(again.header("idempotent-replayed")).isEqualTo("true");
            assertThat(again.json().get("id")).isEqualTo(first.json().get("id"));
            assertThat(again.json().get("reference")).isEqualTo(first.json().get("reference"));
        }
        assertThat(walletBalance(alice)).isEqualTo(50_000);
        assertThat(walletBalance(bob)).isEqualTo(50_000);
        assertThat(dbCount("SELECT COUNT(*) FROM transfers WHERE sender_id = ?", alice.userId()))
                .isEqualTo(1);
        assertThat(dbCount("SELECT COUNT(*) FROM ledger_transactions WHERE idempotency_key = ?", k))
                .isEqualTo(1);

        // Same key, different body: conflict, nothing moves.
        ApiClient.Response conflict = alice.post(
                "/api/v1/transfers", Map.of("recipient", bobEmail, "amountMinor", 50_001, "currency", "INR"), k);
        conflict.expectStatus(409);
        assertThat(conflict.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(walletBalance(alice)).isEqualTo(50_000);

        // Keys are scoped per user: Bob may use the same key string independently.
        bob.post(
                        "/api/v1/transfers",
                        Map.of(
                                "recipient",
                                alice.get("/api/v1/auth/me").json().get("email").asString(),
                                "amountMinor",
                                1_000,
                                "currency",
                                "INR"),
                        k)
                .expectStatus(201);
    }

    @Test
    void failedOperationDoesNotConsumeIdempotencyKey() {
        ApiClient alice = customer("retry");
        ApiClient bob = customer("retry-bob");
        String bobEmail = bob.get("/api/v1/auth/me").json().get("email").asString();
        String k = key();
        Map<String, Object> body = Map.of("recipient", bobEmail, "amountMinor", 5_000, "currency", "INR");
        alice.post("/api/v1/transfers", body, k).expectStatus(422);
        topUp(alice, 5_000);
        alice.post("/api/v1/transfers", body, k).expectStatus(201);
        assertThat(walletBalance(bob)).isEqualTo(5_000);
    }

    @Test
    void statementShowsOpeningClosingAndRunningBalances() {
        ApiClient c = customer("statement");
        ApiClient other = customer("statement-other");
        topUp(c, 10_000);
        topUp(c, 5_000);
        c.post(
                        "/api/v1/transfers",
                        Map.of(
                                "recipient",
                                other.get("/api/v1/auth/me").json().get("email").asString(),
                                "amountMinor",
                                3_000,
                                "currency",
                                "INR"),
                        key())
                .expectStatus(201);
        JsonNode st = c.get("/api/v1/wallet/statement").expectStatus(200).json();
        assertThat(st.get("openingBalanceMinor").asLong()).isZero();
        assertThat(st.get("closingBalanceMinor").asLong()).isEqualTo(12_000);
        assertThat(st.get("totalCreditsMinor").asLong()).isEqualTo(15_000);
        assertThat(st.get("totalDebitsMinor").asLong()).isEqualTo(3_000);
        JsonNode lines = st.get("lines");
        assertThat(lines.size()).isEqualTo(3);
        assertThat(lines.get(0).get("direction").asString()).isEqualTo("OUT");
        assertThat(lines.get(0).get("runningBalanceMinor").asLong()).isEqualTo(12_000);
        assertThat(lines.get(2).get("runningBalanceMinor").asLong()).isEqualTo(10_000);
    }

    @Test
    void transactionHistoryFiltersAndPaginatesInDatabase() {
        ApiClient c = customer("history");
        ApiClient other = customer("history-other");
        String otherEmail = other.get("/api/v1/auth/me").json().get("email").asString();
        for (int i = 0; i < 5; i++) {
            topUp(c, 1_000 + i);
        }
        c.post(
                        "/api/v1/transfers",
                        Map.of(
                                "recipient",
                                otherEmail,
                                "amountMinor",
                                700,
                                "currency",
                                "INR",
                                "description",
                                "Coffee beans"),
                        key())
                .expectStatus(201);

        JsonNode page0 =
                c.get("/api/v1/transactions?size=4&page=0").expectStatus(200).json();
        assertThat(page0.get("totalItems").asLong()).isEqualTo(6);
        assertThat(page0.get("totalPages").asInt()).isEqualTo(2);
        assertThat(page0.get("items").size()).isEqualTo(4);
        assertThat(page0.get("items").get(0).get("type").asString()).isEqualTo("TRANSFER");
        assertThat(page0.get("items").get(0).get("direction").asString()).isEqualTo("OUT");
        assertThat(page0.get("items").get(0).get("counterparty").asString()).isEqualTo("history-other T.");

        assertThat(c.get("/api/v1/transactions?type=TOP_UP")
                        .json()
                        .get("totalItems")
                        .asLong())
                .isEqualTo(5);
        assertThat(c.get("/api/v1/transactions?direction=OUT")
                        .json()
                        .get("totalItems")
                        .asLong())
                .isEqualTo(1);
        assertThat(c.get("/api/v1/transactions?q=coffee")
                        .json()
                        .get("totalItems")
                        .asLong())
                .isEqualTo(1);
        assertThat(c.get("/api/v1/transactions?currency=USD")
                        .json()
                        .get("totalItems")
                        .asLong())
                .isZero();
        assertThat(c.get("/api/v1/transactions?from=2999-01-01T00:00:00Z")
                        .json()
                        .get("totalItems")
                        .asLong())
                .isZero();
        JsonNode byAmount =
                c.get("/api/v1/transactions?type=TOP_UP&sort=amount,desc").json();
        assertThat(byAmount.get("items").get(0).get("amountMinor").asLong()).isEqualTo(1_004);
        c.get("/api/v1/transactions?type=BOGUS").expectStatus(400);
        c.get("/api/v1/transactions?size=1000").expectStatus(200);

        // Receiver sees the same transaction as incoming.
        JsonNode otherItems = other.get("/api/v1/transactions").json().get("items");
        assertThat(otherItems.get(0).get("direction").asString()).isEqualTo("IN");
        assertThat(otherItems.get(0).get("amountMinor").asLong()).isEqualTo(700);
        UUID txId = UUID.fromString(otherItems.get(0).get("id").asString());
        JsonNode detail =
                other.get("/api/v1/transactions/" + txId).expectStatus(200).json();
        assertThat(detail.get("related").get("kind").asString()).isEqualTo("TRANSFER");
        // Idempotency key only disclosed to the initiator.
        assertThat(detail.has("idempotencyKey")).isFalse();
        assertThat(c.get("/api/v1/transactions/" + txId)
                        .json()
                        .get("idempotencyKey")
                        .asString())
                .startsWith("it-");
    }

    @Test
    void dashboardSummaryComesFromLedger() {
        ApiClient c = customer("dash");
        ApiClient other = customer("dash-other");
        topUp(c, 20_000);
        c.post(
                        "/api/v1/transfers",
                        Map.of(
                                "recipient",
                                other.get("/api/v1/auth/me").json().get("email").asString(),
                                "amountMinor",
                                4_000,
                                "currency",
                                "INR"),
                        key())
                .expectStatus(201);
        JsonNode s = c.get("/api/v1/dashboard/summary").expectStatus(200).json();
        assertThat(s.get("balanceMinor").asLong()).isEqualTo(16_000);
        assertThat(s.get("totalInMinor").asLong()).isEqualTo(20_000);
        assertThat(s.get("totalOutMinor").asLong()).isEqualTo(4_000);
        assertThat(s.get("last30Days").size()).isEqualTo(30);
        long seriesIn = 0;
        for (JsonNode d : s.get("last30Days")) {
            seriesIn += d.get("inMinor").asLong();
        }
        assertThat(seriesIn).isEqualTo(20_000);
        JsonNode notifications =
                other.get("/api/v1/notifications").expectStatus(200).json();
        assertThat(notifications.get("unreadCount").asLong()).isEqualTo(1);
        other.post("/api/v1/notifications/read", null, null).expectStatus(204);
        assertThat(other.get("/api/v1/notifications").json().get("unreadCount").asLong())
                .isZero();
    }
}
