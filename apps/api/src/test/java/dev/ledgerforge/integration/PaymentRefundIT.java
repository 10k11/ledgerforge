package dev.ledgerforge.integration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerforge.support.ApiClient;
import dev.ledgerforge.support.IntegrationTestBase;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class PaymentRefundIT extends IntegrationTestBase {

    private JsonNode createIntent(ApiClient customer, ApiClient merchant, long amount) {
        Map<String, Object> body = new HashMap<>();
        body.put("merchantId", merchant.userId().toString());
        body.put("amountMinor", amount);
        body.put("currency", "INR");
        body.put("description", "Order");
        body.put("metadata", Map.of("orderId", "A-1"));
        return customer.post("/api/v1/payment-intents", body, key())
                .expectStatus(201)
                .json();
    }

    private JsonNode confirm(ApiClient customer, JsonNode intent) {
        return customer.post("/api/v1/payment-intents/" + intent.get("id").asString() + "/confirm", null, key())
                .expectStatus(200)
                .json();
    }

    private long merchantBalance(ApiClient m) {
        return m.get("/api/v1/merchant/balance")
                .expectStatus(200)
                .json()
                .get("availableMinor")
                .asLong();
    }

    @Test
    void merchantDirectoryListsActiveMerchants() {
        ApiClient m = merchant("Directory Cafe");
        ApiClient c = customer("dir");
        JsonNode list = c.get("/api/v1/merchants?q=directory").expectStatus(200).json();
        assertThat(list.size()).isGreaterThanOrEqualTo(1);
        assertThat(list.toString()).contains(m.userId().toString()).contains("INR");
    }

    @Test
    void paymentCaptureSplitsFeeAndPostsBalancedEntries() {
        ApiClient m = merchant("Fee Bakery");
        ApiClient c = customer("payer");
        topUp(c, 150_000);
        JsonNode intent = createIntent(c, m, 100_000);
        assertThat(intent.get("status").asString()).isEqualTo("CREATED");
        assertThat(intent.get("platformFeeMinor").asLong()).isEqualTo(2_000);
        assertThat(intent.get("merchantNetMinor").asLong()).isEqualTo(98_000);
        assertThat(intent.get("metadata").get("orderId").asString()).isEqualTo("A-1");
        // Nothing moves on creation.
        assertThat(walletBalance(c)).isEqualTo(150_000);

        JsonNode captured = confirm(c, intent);
        assertThat(captured.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(captured.get("capturedAt").isNull()).isFalse();
        assertThat(walletBalance(c)).isEqualTo(50_000);
        assertThat(merchantBalance(m)).isEqualTo(98_000);

        JsonNode ledger = c.get("/api/v1/transactions/"
                        + captured.get("ledgerTransactionId").asString() + "/ledger")
                .expectStatus(200)
                .json();
        assertThat(ledger.get("balanced").asBoolean()).isTrue();
        assertThat(ledger.get("lines").size()).isEqualTo(3);
        assertThat(ledger.get("totalDebitsMinor").asLong()).isEqualTo(100_000);
        assertThat(ledger.toString()).contains("Merchant Payable").contains("Platform Fee Revenue");

        // Merchant sees the payment with masked customer info; ledger visible to merchant too.
        JsonNode merchantView = m.get(
                        "/api/v1/payment-intents/" + intent.get("id").asString())
                .expectStatus(200)
                .json();
        assertThat(merchantView.get("customer").get("maskedEmail").asString()).contains("•••");
        m.get("/api/v1/transactions/" + captured.get("ledgerTransactionId").asString() + "/ledger")
                .expectStatus(200);
        assertThat(m.get("/api/v1/merchant/payments").json().get("totalItems").asLong())
                .isEqualTo(1);

        // Outbox event written in the same transaction as the capture.
        assertThat(dbCount(
                        "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ?::uuid AND event_type = 'payment.succeeded'",
                        intent.get("id").asString()))
                .isEqualTo(1);
    }

    @Test
    void ownershipIsEnforcedOnPayments() {
        ApiClient m = merchant("Private Shop");
        ApiClient otherMerchant = merchant("Nosy Shop");
        ApiClient c = customer("owner");
        ApiClient stranger = customer("stranger");
        topUp(c, 10_000);
        JsonNode intent = createIntent(c, m, 5_000);
        String id = intent.get("id").asString();

        stranger.get("/api/v1/payment-intents/" + id).expectStatus(404);
        otherMerchant.get("/api/v1/payment-intents/" + id).expectStatus(404);
        stranger.post("/api/v1/payment-intents/" + id + "/confirm", null, key()).expectStatus(404);
        stranger.post("/api/v1/payment-intents/" + id + "/cancel", null, null).expectStatus(404);
        assertThat(stranger.get("/api/v1/payment-intents")
                        .json()
                        .get("totalItems")
                        .asLong())
                .isZero();
        // Merchants cannot create or confirm payments.
        m.post("/api/v1/payment-intents/" + id + "/confirm", null, key()).expectStatus(403);
        confirm(c, intent);
        // Only the payment's merchant may refund it.
        otherMerchant
                .post("/api/v1/payments/" + id + "/refunds", Map.of(), key())
                .expectStatus(404);
        c.post("/api/v1/payments/" + id + "/refunds", Map.of(), key()).expectStatus(404);
        // Customers cannot reach merchant endpoints at all.
        c.get("/api/v1/merchant/dashboard").expectStatus(403);
    }

    @Test
    void insufficientFundsLeavesIntentConfirmable() {
        ApiClient m = merchant("Patient Shop");
        ApiClient c = customer("poor");
        topUp(c, 1_000);
        JsonNode intent = createIntent(c, m, 5_000);
        ApiClient.Response r =
                c.post("/api/v1/payment-intents/" + intent.get("id").asString() + "/confirm", null, key());
        r.expectStatus(422);
        assertThat(r.code()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(c.get("/api/v1/payment-intents/" + intent.get("id").asString())
                        .json()
                        .get("status")
                        .asString())
                .isEqualTo("CREATED");
        assertThat(walletBalance(c)).isEqualTo(1_000);
        assertThat(merchantBalance(m)).isZero();
        topUp(c, 4_000);
        assertThat(confirm(c, intent).get("status").asString()).isEqualTo("SUCCEEDED");
    }

    @Test
    void sandboxDeclineRuleFailsPaymentWithoutMovingMoney() {
        ApiClient m = merchant("Decline Shop");
        ApiClient c = customer("declined");
        topUp(c, 100_000);
        JsonNode intent = createIntent(c, m, 25_013); // ends in .13 => risk_declined
        JsonNode result = confirm(c, intent);
        assertThat(result.get("status").asString()).isEqualTo("FAILED");
        assertThat(result.get("failureCode").asString()).isEqualTo("risk_declined");
        assertThat(walletBalance(c)).isEqualTo(100_000);
        assertThat(dbCount(
                        "SELECT COUNT(*) FROM outbox_events WHERE aggregate_id = ?::uuid AND event_type = 'payment.failed'",
                        intent.get("id").asString()))
                .isEqualTo(1);
        // A failed payment is terminal.
        c.post("/api/v1/payment-intents/" + intent.get("id").asString() + "/confirm", null, key())
                .expectStatus(409);
    }

    @Test
    void paymentCannotBeCapturedTwiceAndCancelRules() {
        ApiClient m = merchant("Once Shop");
        ApiClient c = customer("twice");
        topUp(c, 100_000);
        JsonNode intent = createIntent(c, m, 10_000);
        String id = intent.get("id").asString();
        String k = key();
        c.post("/api/v1/payment-intents/" + id + "/confirm", null, k).expectStatus(200);
        // Same key: replay of the original result.
        ApiClient.Response replay =
                c.post("/api/v1/payment-intents/" + id + "/confirm", null, k).expectStatus(200);
        assertThat(replay.header("idempotent-replayed")).isEqualTo("true");
        // Different key: state machine refuses a second capture.
        ApiClient.Response again = c.post("/api/v1/payment-intents/" + id + "/confirm", null, key());
        again.expectStatus(409);
        assertThat(again.code()).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(walletBalance(c)).isEqualTo(90_000);
        c.post("/api/v1/payment-intents/" + id + "/cancel", null, null).expectStatus(409);

        JsonNode toCancel = createIntent(c, m, 3_000);
        String cid = toCancel.get("id").asString();
        assertThat(c.post("/api/v1/payment-intents/" + cid + "/cancel", null, null)
                        .expectStatus(200)
                        .json()
                        .get("status")
                        .asString())
                .isEqualTo("CANCELLED");
        c.post("/api/v1/payment-intents/" + cid + "/cancel", null, null).expectStatus(200);
        c.post("/api/v1/payment-intents/" + cid + "/confirm", null, key()).expectStatus(409);
    }

    @Test
    void paymentValidation() {
        ApiClient m = merchant("Validating Shop");
        ApiClient c = customer("val");
        Map<String, Object> body = new HashMap<>();
        body.put("merchantId", m.userId().toString());
        body.put("amountMinor", 100);
        body.put("currency", "USD");
        assertThat(c.post("/api/v1/payment-intents", body, key())
                        .expectStatus(422)
                        .code())
                .isEqualTo("CURRENCY_MISMATCH");
        body.put("currency", "INR");
        body.put("merchantId", c.userId().toString());
        c.post("/api/v1/payment-intents", body, key()).expectStatus(404);
        body.put("merchantId", m.userId().toString());
        body.put("amountMinor", 0);
        c.post("/api/v1/payment-intents", body, key()).expectStatus(400);
    }

    @Test
    void fullRefundReversesOriginalPostings() {
        ApiClient m = merchant("Refund Store");
        ApiClient c = customer("refund-full");
        topUp(c, 100_000);
        JsonNode intent = confirm(c, createIntent(c, m, 100_000));
        String id = intent.get("id").asString();

        JsonNode refund = m.post("/api/v1/payments/" + id + "/refunds", Map.of("reason", "Damaged"), key())
                .expectStatus(201)
                .json();
        assertThat(refund.get("amountMinor").asLong()).isEqualTo(100_000);
        assertThat(refund.get("feeReversalMinor").asLong()).isEqualTo(2_000);
        assertThat(refund.get("merchantDebitMinor").asLong()).isEqualTo(98_000);
        assertThat(refund.get("paymentStatus").asString()).isEqualTo("REFUNDED");
        assertThat(walletBalance(c)).isEqualTo(100_000);
        assertThat(merchantBalance(m)).isZero();

        JsonNode ledger = m.get("/api/v1/transactions/"
                        + refund.get("ledgerTransactionId").asString() + "/ledger")
                .expectStatus(200)
                .json();
        assertThat(ledger.get("balanced").asBoolean()).isTrue();
        assertThat(ledger.get("totalDebitsMinor").asLong()).isEqualTo(100_000);
        JsonNode detail = c.get("/api/v1/transactions/"
                        + refund.get("ledgerTransactionId").asString())
                .json();
        assertThat(detail.get("type").asString()).isEqualTo("REFUND");
        assertThat(detail.get("reversesTransactionId").asString())
                .isEqualTo(intent.get("ledgerTransactionId").asString());

        assertThat(m.post("/api/v1/payments/" + id + "/refunds", Map.of(), key())
                        .expectStatus(409)
                        .code())
                .isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(c.get("/api/v1/payments/" + id + "/refunds")
                        .expectStatus(200)
                        .json()
                        .size())
                .isEqualTo(1);
    }

    @Test
    void partialRefundsReconcileExactlyAndCannotExceedRemaining() {
        ApiClient m = merchant("Partial Store");
        ApiClient c = customer("refund-partial");
        topUp(c, 100_000);
        JsonNode intent = confirm(c, createIntent(c, m, 99_999)); // fee = 2000 (1999.98 rounded)
        String id = intent.get("id").asString();
        long fee = intent.get("platformFeeMinor").asLong();
        assertThat(fee).isEqualTo(2_000);

        JsonNode r1 = m.post("/api/v1/payments/" + id + "/refunds", Map.of("amountMinor", 33_333), key())
                .expectStatus(201)
                .json();
        assertThat(r1.get("paymentStatus").asString()).isEqualTo("PARTIALLY_REFUNDED");
        JsonNode tooMuch = m.post("/api/v1/payments/" + id + "/refunds", Map.of("amountMinor", 66_667), key())
                .expectStatus(422)
                .json();
        assertThat(tooMuch.get("code").asString()).isEqualTo("REFUND_EXCEEDS_REMAINING");
        assertThat(tooMuch.get("refundableAmountMinor").asLong()).isEqualTo(66_666);
        JsonNode r2 = m.post("/api/v1/payments/" + id + "/refunds", Map.of("amountMinor", 1), key())
                .expectStatus(201)
                .json();
        JsonNode r3 = m.post("/api/v1/payments/" + id + "/refunds", Map.of(), key())
                .expectStatus(201)
                .json();
        assertThat(r3.get("amountMinor").asLong()).isEqualTo(66_665);
        assertThat(r3.get("paymentStatus").asString()).isEqualTo("REFUNDED");

        long feeReversed = r1.get("feeReversalMinor").asLong()
                + r2.get("feeReversalMinor").asLong()
                + r3.get("feeReversalMinor").asLong();
        long merchantDebited = r1.get("merchantDebitMinor").asLong()
                + r2.get("merchantDebitMinor").asLong()
                + r3.get("merchantDebitMinor").asLong();
        assertThat(feeReversed).isEqualTo(fee);
        assertThat(merchantDebited).isEqualTo(intent.get("merchantNetMinor").asLong());
        assertThat(walletBalance(c)).isEqualTo(100_000);
        assertThat(merchantBalance(m)).isZero();
    }

    @Test
    void refundRequiresMerchantFundsAfterPayout() {
        ApiClient m = merchant("Paid Out Store");
        ApiClient c = customer("payout-refund");
        topUp(c, 10_000);
        JsonNode intent = confirm(c, createIntent(c, m, 10_000));
        m.post("/api/v1/merchant/payouts", Map.of("amountMinor", 9_800), key()).expectStatus(201);
        assertThat(merchantBalance(m)).isZero();
        ApiClient.Response r = m.post("/api/v1/payments/" + intent.get("id").asString() + "/refunds", Map.of(), key());
        r.expectStatus(422);
        assertThat(r.code()).isEqualTo("MERCHANT_INSUFFICIENT_BALANCE");
        assertThat(walletBalance(c)).isZero();
    }

    @Test
    void merchantDashboardReportsAndPayouts() {
        ApiClient m = merchant("Report Store");
        ApiClient c = customer("report");
        topUp(c, 500_000);
        confirm(c, createIntent(c, m, 100_000));
        JsonNode second = confirm(c, createIntent(c, m, 50_000));
        confirm(c, createIntent(c, m, 10_013)); // declined
        createIntent(c, m, 7_000); // pending
        m.post("/api/v1/payments/" + second.get("id").asString() + "/refunds", Map.of("amountMinor", 10_000), key())
                .expectStatus(201);

        JsonNode d = m.get("/api/v1/merchant/dashboard").expectStatus(200).json();
        assertThat(d.get("businessName").asString()).isEqualTo("Report Store");
        assertThat(d.get("grossVolumeMinor").asLong()).isEqualTo(150_000);
        assertThat(d.get("platformFeesMinor").asLong()).isEqualTo(3_000);
        assertThat(d.get("netVolumeMinor").asLong()).isEqualTo(147_000);
        assertThat(d.get("refundedMinor").asLong()).isEqualTo(10_000);
        assertThat(d.get("successfulPayments").asLong()).isEqualTo(2);
        assertThat(d.get("failedPayments").asLong()).isEqualTo(1);
        assertThat(d.get("pendingPayments").asLong()).isEqualTo(1);
        assertThat(d.get("balanceMinor").asLong()).isEqualTo(147_000 - 9_800);

        JsonNode report = m.get("/api/v1/merchant/reports").expectStatus(200).json();
        assertThat(report.get("grossVolumeMinor").asLong()).isEqualTo(150_000);
        assertThat(report.get("refundedMinor").asLong()).isEqualTo(10_000);
        assertThat(report.get("days").size()).isEqualTo(30);

        JsonNode payout = m.post("/api/v1/merchant/payouts", Map.of("amountMinor", 37_200), key())
                .expectStatus(201)
                .json();
        assertThat(payout.get("balanceAfterMinor").asLong()).isEqualTo(100_000);
        assertThat(m.post("/api/v1/merchant/payouts", Map.of("amountMinor", 100_001), key())
                        .expectStatus(422)
                        .code())
                .isEqualTo("MERCHANT_INSUFFICIENT_BALANCE");
        assertThat(m.get("/api/v1/merchant/payouts").json().get("totalItems").asLong())
                .isEqualTo(1);
        assertThat(m.get("/api/v1/merchant/payments?status=FAILED")
                        .json()
                        .get("totalItems")
                        .asLong())
                .isEqualTo(1);
    }
}
