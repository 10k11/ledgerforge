package dev.ledgerforge.support;

import dev.ledgerforge.common.money.Currency;
import dev.ledgerforge.user.AccountProvisioningService;
import dev.ledgerforge.user.Role;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Boots the full application on a random port against a real PostgreSQL (Testcontainers). The
 * container is a JVM-wide singleton so every integration test class shares one database and one
 * cached Spring context; tests isolate themselves by creating fresh users.
 *
 * <p>Background webhook workers are disabled so tests drive dispatch/delivery deterministically.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.security.jwt.secret=integration-test-secret-that-is-at-least-32-bytes",
            "app.security.bcrypt-strength=4",
            "app.rate-limit.enabled=false",
            "app.webhooks.workers-enabled=false",
            "app.webhooks.allow-private-targets=true",
            "app.webhooks.allow-plain-http=true",
            "app.webhooks.max-attempts=3",
            "app.webhooks.base-backoff=PT1S",
            "app.sandbox.enabled=true",
            "app.seed.enabled=false",
            "spring.datasource.hikari.maximum-pool-size=40"
        })
public abstract class IntegrationTestBase {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected AccountProvisioningService provisioning;

    @Autowired
    protected PlatformTransactionManager txManager;

    protected static final String PASSWORD = "Integration-Pass-42";

    protected ApiClient client() {
        return new ApiClient("http://localhost:" + port);
    }

    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@ledgerforge.test";
    }

    /** Registers and signs in a new customer. */
    protected ApiClient customer(String name) {
        ApiClient c = client();
        c.register(uniqueEmail(name), name + " Tester", PASSWORD, "CUSTOMER", "INR", null);
        return c;
    }

    protected ApiClient merchant(String businessName) {
        ApiClient c = client();
        c.register(uniqueEmail("merchant"), "Owner of " + businessName, PASSWORD, "MERCHANT", "INR", businessName);
        return c;
    }

    protected ApiClient admin() {
        String email = uniqueEmail("admin");
        new TransactionTemplate(txManager)
                .executeWithoutResult(s -> provisioning.provision(new AccountProvisioningService.NewUser(
                        email, "Admin", PASSWORD, Role.ADMIN, Currency.INR, null, null)));
        ApiClient c = client();
        c.login(email, PASSWORD).expectStatus(200);
        return c;
    }

    protected static String key() {
        return "it-" + UUID.randomUUID();
    }

    protected void topUp(ApiClient c, long amountMinor) {
        c.post("/api/v1/wallet/sandbox/top-ups", Map.of("amountMinor", amountMinor, "currency", "INR"), key())
                .expectStatus(201);
    }

    protected long walletBalance(ApiClient c) {
        return c.get("/api/v1/wallet/balance")
                .expectStatus(200)
                .json()
                .get("balanceMinor")
                .asLong();
    }

    protected long dbCount(String sql, Object... args) {
        Long v = jdbc.queryForObject(sql, Long.class, args);
        return v == null ? 0 : v;
    }
}
