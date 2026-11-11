package dev.ledgerforge.integration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerforge.ledger.ReconciliationService;
import dev.ledgerforge.support.ApiClient;
import dev.ledgerforge.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Races real HTTP requests against the application and PostgreSQL, then verifies the final
 * database state (balances, ledger rows, reconciliation), not just response codes.
 */
class ConcurrencyIT extends IntegrationTestBase {

    @Autowired
    ReconciliationService reconciliation;

    /** Runs all tasks at the same instant (start gate) and returns their results. */
    private static <T> List<T> race(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await(10, TimeUnit.SECONDS);
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static String email(ApiClient c) {
        return c.get("/api/v1/auth/me").json().get("email").asString();
    }

    private long ledgerTransactionsFrom(ApiClient c) {
        return dbCount(
                "SELECT COUNT(*) FROM ledger_transactions WHERE initiated_by = ? AND transaction_type = 'TRANSFER'",
                c.userId());
    }

    @Test
    void twoConcurrentSpendsCannotOverdrawWallet() throws Exception {
        ApiClient sender = customer("double-spend");
        ApiClient recipient = customer("double-spend-to");
        topUp(sender, 50_000); // ₹500.00
        String to = email(recipient);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            tasks.add(() -> sender.post(
                            "/api/v1/transfers",
                            Map.of("recipient", to, "amountMinor", 40_000, "currency", "INR"),
                            key())
                    .status());
        }
        List<Integer> statuses = race(tasks);
        assertThat(statuses).containsExactlyInAnyOrder(201, 422);
        assertThat(walletBalance(sender)).isEqualTo(10_000); // ₹100.00
        assertThat(walletBalance(recipient)).isEqualTo(40_000);
        assertThat(ledgerTransactionsFrom(sender)).isEqualTo(1);
        assertThat(reconciliation.reconcile().healthy()).isTrue();
    }

    @Test
    void manyConcurrentSpendsSucceedExactlyAsFarAsFundsAllow() throws Exception {
        ApiClient sender = customer("burst");
        ApiClient recipient = customer("burst-to");
        topUp(sender, 50_000);
        String to = email(recipient);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            tasks.add(() -> sender.post(
                            "/api/v1/transfers",
                            Map.of("recipient", to, "amountMinor", 10_000, "currency", "INR"),
                            key())
                    .status());
        }
        List<Integer> statuses = race(tasks);
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(5);
        assertThat(statuses.stream().filter(s -> s == 422).count()).isEqualTo(15);
        assertThat(walletBalance(sender)).isZero();
        assertThat(walletBalance(recipient)).isEqualTo(50_000);
        assertThat(ledgerTransactionsFrom(sender)).isEqualTo(5);
    }

    @Test
    void concurrentIdenticalIdempotentRequestsCreateOneTransaction() throws Exception {
        ApiClient sender = customer("idem-race");
        ApiClient recipient = customer("idem-race-to");
        topUp(sender, 100_000);
        String to = email(recipient);
        String sharedKey = key();
        Map<String, Object> body = Map.of("recipient", to, "amountMinor", 50_000, "currency", "INR");
        List<Callable<ApiClient.Response>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> sender.post("/api/v1/transfers", body, sharedKey));
        }
        List<ApiClient.Response> responses = race(tasks);
        assertThat(responses).allSatisfy(r -> assertThat(r.status()).isEqualTo(201));
        assertThat(responses.stream().map(r -> r.json().get("id").asString()).distinct())
                .hasSize(1);
        assertThat(responses.stream()
                        .filter(r -> "false".equals(r.header("idempotent-replayed")))
                        .count())
                .isEqualTo(1);
        assertThat(walletBalance(sender)).isEqualTo(50_000);
        assertThat(walletBalance(recipient)).isEqualTo(50_000);
        assertThat(dbCount("SELECT COUNT(*) FROM ledger_transactions WHERE idempotency_key = ?", sharedKey))
                .isEqualTo(1);
        assertThat(dbCount("SELECT COUNT(*) FROM idempotency_records WHERE idempotency_key = ?", sharedKey))
                .isEqualTo(1);
    }

    @Test
    void concurrentConfirmationsCaptureAPaymentOnce() throws Exception {
        ApiClient m = merchant("Race Cafe");
        ApiClient c = customer("confirm-race");
        topUp(c, 100_000);
        Map<String, Object> body = new HashMap<>();
        body.put("merchantId", m.userId().toString());
        body.put("amountMinor", 30_000);
        body.put("currency", "INR");
        String id = c.post("/api/v1/payment-intents", body, key())
                .expectStatus(201)
                .json()
                .get("id")
                .asString();
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> c.post("/api/v1/payment-intents/" + id + "/confirm", null, key())
                    .status());
        }
        List<Integer> statuses = race(tasks);
        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(7);
        assertThat(walletBalance(c)).isEqualTo(70_000);
        assertThat(dbCount("SELECT COUNT(*) FROM ledger_transactions WHERE related_entity_id = ?::uuid", id))
                .isEqualTo(1);
    }

    @Test
    void concurrentRefundsNeverExceedCapturedAmount() throws Exception {
        ApiClient m = merchant("Refund Race");
        ApiClient c = customer("refund-race");
        topUp(c, 100_000);
        Map<String, Object> body = new HashMap<>();
        body.put("merchantId", m.userId().toString());
        body.put("amountMinor", 100_000);
        body.put("currency", "INR");
        String id = c.post("/api/v1/payment-intents", body, key())
                .expectStatus(201)
                .json()
                .get("id")
                .asString();
        c.post("/api/v1/payment-intents/" + id + "/confirm", null, key()).expectStatus(200);

        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            tasks.add(() -> m.post("/api/v1/payments/" + id + "/refunds", Map.of("amountMinor", 30_000), key())
                    .status());
        }
        List<Integer> statuses = race(tasks);
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(3);
        assertThat(statuses.stream().filter(s -> s == 422).count()).isEqualTo(7);
        JsonNode payment = c.get("/api/v1/payment-intents/" + id).json();
        assertThat(payment.get("refundedAmountMinor").asLong()).isEqualTo(90_000);
        assertThat(payment.get("status").asString()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(walletBalance(c)).isEqualTo(90_000);
        assertThat(dbCount("SELECT COALESCE(SUM(amount_minor), 0) FROM refunds WHERE payment_intent_id = ?::uuid", id))
                .isEqualTo(90_000);
        assertThat(reconciliation.reconcile().healthy()).isTrue();
    }

    @Test
    void randomConcurrentTransfersConserveMoneyAndNeverDeadlock() throws Exception {
        int users = 5;
        List<ApiClient> clients = new ArrayList<>();
        List<String> emails = new ArrayList<>();
        for (int i = 0; i < users; i++) {
            ApiClient c = customer("mesh" + i);
            topUp(c, 100_000);
            clients.add(c);
            emails.add(email(c));
        }
        Random random = new Random(42);
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            int from = random.nextInt(users);
            int to = (from + 1 + random.nextInt(users - 1)) % users; // A->B and B->A both occur
            long amount = 1_000 + random.nextInt(30_000);
            tasks.add(() -> clients.get(from)
                    .post(
                            "/api/v1/transfers",
                            Map.of("recipient", emails.get(to), "amountMinor", amount, "currency", "INR"),
                            key())
                    .status());
        }
        List<Integer> statuses = race(tasks);
        // Every request either moved money or was rejected for insufficient funds: no 409/500 from deadlocks.
        assertThat(statuses).allSatisfy(s -> assertThat(s).isIn(201, 422));
        long total = 0;
        for (ApiClient c : clients) {
            long b = walletBalance(c);
            assertThat(b).isGreaterThanOrEqualTo(0);
            total += b;
        }
        assertThat(total).isEqualTo(users * 100_000L);
        ReconciliationService.Report report = reconciliation.reconcile();
        assertThat(report.balanceMismatches()).isEmpty();
        assertThat(report.unbalancedTransactions()).isEmpty();
        assertThat(report.healthy()).isTrue();
    }

    @Test
    void concurrentTopUpsRespectDailyLimit() throws Exception {
        ApiClient c = customer("daily");
        // Daily limit is 50,000,000; 6 concurrent top-ups of 10,000,000 => at most 5 can succeed.
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> c.post(
                            "/api/v1/wallet/sandbox/top-ups",
                            Map.of("amountMinor", 10_000_000, "currency", "INR"),
                            key())
                    .status());
        }
        List<Integer> statuses = race(tasks);
        assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(5);
        assertThat(walletBalance(c)).isEqualTo(50_000_000);
    }
}
