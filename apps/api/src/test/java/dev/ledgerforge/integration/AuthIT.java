package dev.ledgerforge.integration;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerforge.support.ApiClient;
import dev.ledgerforge.support.IntegrationTestBase;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class AuthIT extends IntegrationTestBase {

    @Test
    void registerCreatesCustomerWithWalletAndSession() {
        ApiClient c = client();
        String email = uniqueEmail("reg");
        ApiClient.Response r = c.register(email, "Asha Verma", PASSWORD, "CUSTOMER", "INR", null);
        JsonNode body = r.json();
        assertThat(body.get("tokenType").asString()).isEqualTo("Bearer");
        assertThat(body.get("user").get("role").asString()).isEqualTo("CUSTOMER");
        assertThat(r.header("set-cookie"))
                .contains("lf_refresh=")
                .contains("HttpOnly")
                .contains("SameSite=Strict");
        assertThat(r.header("set-cookie")).contains("Path=/api/v1/auth");

        JsonNode wallet = c.get("/api/v1/wallet").expectStatus(200).json();
        assertThat(wallet.get("balanceMinor").asLong()).isZero();
        assertThat(wallet.get("currency").asString()).isEqualTo("INR");
        assertThat(dbCount("SELECT COUNT(*) FROM ledger_accounts WHERE owner_id = ?", c.userId()))
                .isEqualTo(1);
        // Only the hash of the refresh token is stored.
        assertThat(dbCount("SELECT COUNT(*) FROM refresh_sessions WHERE token_hash = ?", c.refreshCookie()))
                .isZero();
        // The password is stored as a BCrypt hash.
        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, c.userId());
        assertThat(hash).startsWith("$2").doesNotContain(PASSWORD);
    }

    @Test
    void registrationValidation() {
        ApiClient c = client();
        ApiClient.Response weak = c.post(
                "/api/v1/auth/register",
                Map.of(
                        "fullName",
                        "Weak",
                        "email",
                        uniqueEmail("weak"),
                        "password",
                        "password123",
                        "accountType",
                        "CUSTOMER",
                        "acceptTerms",
                        true),
                null);
        weak.expectStatus(400);
        assertThat(weak.code()).isEqualTo("VALIDATION_FAILED");

        ApiClient.Response admin = c.post(
                "/api/v1/auth/register",
                Map.of(
                        "fullName",
                        "Sneaky",
                        "email",
                        uniqueEmail("sneaky"),
                        "password",
                        PASSWORD,
                        "accountType",
                        "ADMIN",
                        "acceptTerms",
                        true),
                null);
        admin.expectStatus(400); // ADMIN is not a registrable account type

        ApiClient.Response noTerms = c.post(
                "/api/v1/auth/register",
                Map.of(
                        "fullName",
                        "No Terms",
                        "email",
                        uniqueEmail("terms"),
                        "password",
                        PASSWORD,
                        "accountType",
                        "CUSTOMER",
                        "acceptTerms",
                        false),
                null);
        noTerms.expectStatus(400);

        ApiClient.Response merchantNoBusiness = c.post(
                "/api/v1/auth/register",
                Map.of(
                        "fullName",
                        "M",
                        "email",
                        uniqueEmail("m"),
                        "password",
                        PASSWORD,
                        "accountType",
                        "MERCHANT",
                        "acceptTerms",
                        true),
                null);
        merchantNoBusiness.expectStatus(400);

        String email = uniqueEmail("dupe");
        client().register(email, "First", PASSWORD, "CUSTOMER", "INR", null);
        ApiClient.Response dupe = client().post(
                        "/api/v1/auth/register",
                        Map.of(
                                "fullName",
                                "Second",
                                "email",
                                email.toUpperCase(),
                                "password",
                                PASSWORD,
                                "accountType",
                                "CUSTOMER",
                                "acceptTerms",
                                true),
                        null);
        dupe.expectStatus(409);
        assertThat(dupe.code()).isEqualTo("EMAIL_ALREADY_REGISTERED");
    }

    @Test
    void loginMeAndGenericErrors() {
        String email = uniqueEmail("login");
        client().register(email, "Login User", PASSWORD, "CUSTOMER", "INR", null);
        ApiClient c = client();
        c.login(email, PASSWORD).expectStatus(200);
        JsonNode me = c.get("/api/v1/auth/me").expectStatus(200).json();
        assertThat(me.get("email").asString()).isEqualTo(email);

        ApiClient.Response wrong = client().login(email, "Wrong-Password-1");
        wrong.expectStatus(401);
        ApiClient.Response unknown = client().login(uniqueEmail("nobody"), "Wrong-Password-1");
        unknown.expectStatus(401);
        // Same message whether or not the account exists (no user enumeration).
        assertThat(wrong.json().get("detail")).isEqualTo(unknown.json().get("detail"));
        assertThat(wrong.json().get("correlationId")).isNotNull();
    }

    @Test
    void accountLocksAfterRepeatedFailures() {
        String email = uniqueEmail("lock");
        client().register(email, "Lock User", PASSWORD, "CUSTOMER", "INR", null);
        for (int i = 0; i < 5; i++) {
            client().login(email, "Wrong-Password-" + i).expectStatus(401);
        }
        ApiClient.Response locked = client().login(email, PASSWORD);
        locked.expectStatus(423);
        assertThat(locked.code()).isEqualTo("ACCOUNT_LOCKED");
    }

    @Test
    void protectedEndpointsRequireValidToken() {
        ApiClient anon = client();
        anon.get("/api/v1/wallet").expectStatus(401);
        anon.setAccessToken("not-a-jwt");
        assertThat(anon.get("/api/v1/auth/me").expectStatus(401).code()).isEqualTo("UNAUTHENTICATED");
        // A token signed with another key is rejected.
        anon.setAccessToken("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJl");
        anon.get("/api/v1/auth/me").expectStatus(401);
    }

    @Test
    void refreshRotatesTokenAndRequiresCsrf() {
        ApiClient c = customer("refresh");
        String firstRefresh = c.refreshCookie();

        // Without the CSRF header the cookie-authenticated endpoint is refused.
        ApiClient.Response noCsrf = c.send("POST", "/api/v1/auth/refresh", null, Map.of(), false);
        noCsrf.expectStatus(403);

        c.refresh().expectStatus(200);
        String secondRefresh = c.refreshCookie();
        assertThat(secondRefresh).isNotEqualTo(firstRefresh);
        c.get("/api/v1/auth/me").expectStatus(200);

        // Replaying the rotated token is treated as theft: the whole session family is revoked.
        ApiClient attacker = client();
        attacker.setRefreshCookie(firstRefresh);
        attacker.refresh().expectStatus(401);
        assertThat(dbCount(
                        "SELECT COUNT(*) FROM refresh_sessions WHERE user_id = ? AND revoked_reason = 'REUSE_DETECTED'",
                        c.userId()))
                .isPositive();
        // ...which also kills the legitimate holder's current access token and refresh token.
        c.get("/api/v1/auth/me").expectStatus(401);
        c.refresh().expectStatus(401);
    }

    @Test
    void logoutRevokesSessionImmediately() {
        ApiClient c = customer("logout");
        c.get("/api/v1/auth/me").expectStatus(200);
        ApiClient.Response out = c.logout();
        out.expectStatus(204);
        assertThat(out.header("set-cookie")).contains("lf_refresh=").contains("Max-Age=0");
        c.get("/api/v1/auth/me").expectStatus(401);
    }

    @Test
    void sessionsCanBeListedAndRevokedAndPasswordChangeSignsOutOthers() {
        String email = uniqueEmail("sessions");
        ApiClient first = client();
        first.register(email, "Session User", PASSWORD, "CUSTOMER", "INR", null);
        ApiClient second = client();
        second.login(email, PASSWORD).expectStatus(200);

        JsonNode sessions = first.get("/api/v1/auth/sessions").expectStatus(200).json();
        assertThat(sessions.size()).isEqualTo(2);

        first.post(
                        "/api/v1/auth/password",
                        Map.of("currentPassword", PASSWORD, "newPassword", "Brand-New-Pass-77"),
                        null)
                .expectStatus(204);
        first.get("/api/v1/auth/me").expectStatus(200);
        second.get("/api/v1/auth/me").expectStatus(401);
        client().login(email, "Brand-New-Pass-77").expectStatus(200);
    }

    @Test
    void securityHeadersAndCorrelationIdArePresent() {
        ApiClient c = customer("headers");
        ApiClient.Response r = c.get("/api/v1/auth/me");
        assertThat(r.header("x-request-id")).isNotBlank();
        assertThat(r.header("x-content-type-options")).isEqualTo("nosniff");
        assertThat(r.header("x-frame-options")).isEqualTo("DENY");
        assertThat(r.header("content-security-policy")).contains("default-src 'none'");
    }
}
