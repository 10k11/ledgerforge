package dev.ledgerforge.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerforge.auth.PasswordPolicyTestAccess;
import dev.ledgerforge.common.config.AppProperties;
import dev.ledgerforge.common.error.ApiException;
import dev.ledgerforge.common.error.ErrorCode;
import dev.ledgerforge.common.web.TokenBucketRateLimiter;
import dev.ledgerforge.idempotency.RequestFingerprinter;
import dev.ledgerforge.user.UserDirectory;
import dev.ledgerforge.webhook.SecretCipher;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SecurityPrimitivesTest {

    @Test
    void passwordPolicy() {
        assertThat(PasswordPolicyTestAccess.problems("short1", "a@b.c")).contains("must be at least 10 characters");
        assertThat(PasswordPolicyTestAccess.problems("onlyletterspass", "a@b.c"))
                .contains("must contain a digit");
        assertThat(PasswordPolicyTestAccess.problems("1234567890123", "a@b.c")).contains("must contain a letter");
        assertThat(PasswordPolicyTestAccess.problems("password123", "a@b.c")).contains("is too common");
        assertThat(PasswordPolicyTestAccess.problems("correct-horse-42", "a@b.c"))
                .isEmpty();
    }

    @Test
    void fingerprintIgnoresMapKeyOrderButNotValues() {
        RequestFingerprinter fp = new RequestFingerprinter();
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("amountMinor", 500);
        a.put("currency", "INR");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("currency", "INR");
        b.put("amountMinor", 500);
        assertThat(fp.fingerprint("POST", "/x", a)).isEqualTo(fp.fingerprint("POST", "/x", b));
        b.put("amountMinor", 501);
        assertThat(fp.fingerprint("POST", "/x", a)).isNotEqualTo(fp.fingerprint("POST", "/x", b));
        assertThat(fp.fingerprint("POST", "/x", a)).isNotEqualTo(fp.fingerprint("POST", "/y", a));
    }

    @Test
    void rateLimiterAllowsBurstThenBlocksThenRefills() {
        AtomicLong millis = new AtomicLong(0);
        Clock clock = new Clock() {
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            public Instant instant() {
                return Instant.ofEpochMilli(millis.get());
            }
        };
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(3, clock);
        assertThat(limiter.tryAcquire("k")).isZero();
        assertThat(limiter.tryAcquire("k")).isZero();
        assertThat(limiter.tryAcquire("k")).isZero();
        assertThat(limiter.tryAcquire("k")).isPositive();
        assertThat(limiter.tryAcquire("other")).isZero();
        millis.addAndGet(20_000); // one token per 20s at 3/min
        assertThat(limiter.tryAcquire("k")).isZero();
    }

    @Test
    void secretCipherRoundTripsAndIsRandomised() {
        AppProperties props = new AppProperties(
                "test",
                "http://localhost",
                "http://localhost",
                new AppProperties.Security(
                        new AppProperties.Jwt("x".repeat(40), "i", "a", Duration.ofMinutes(5)),
                        Duration.ofDays(1),
                        false,
                        4,
                        5,
                        Duration.ofMinutes(1),
                        true),
                null,
                null,
                null,
                null,
                new AppProperties.Webhooks(
                        false,
                        false,
                        false,
                        null,
                        3,
                        3,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        1000,
                        10,
                        Duration.ofMinutes(5)),
                null,
                null);
        SecretCipher cipher = new SecretCipher(props);
        String a = cipher.encrypt("whsec_abc");
        String b = cipher.encrypt("whsec_abc");
        assertThat(a).isNotEqualTo(b).doesNotContain("whsec_abc");
        assertThat(cipher.decrypt(a)).isEqualTo("whsec_abc");
        assertThatThrownBy(() -> cipher.decrypt(a.substring(0, a.length() - 4) + "AAAA"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void emailMaskingAndShortNames() {
        assertThat(UserDirectory.maskEmail("asha.verma@example.com")).isEqualTo("as•••@example.com");
        assertThat(UserDirectory.maskEmail("a@example.com")).isEqualTo("a•••@example.com");
        assertThat(UserDirectory.shortName("Asha Verma")).isEqualTo("Asha V.");
        assertThat(UserDirectory.shortName("Madonna")).isEqualTo("Madonna");
    }

    @Test
    void currencyParsing() {
        assertThat(dev.ledgerforge.common.money.Currency.parse("inr"))
                .isEqualTo(dev.ledgerforge.common.money.Currency.INR);
        assertThatThrownBy(() -> dev.ledgerforge.common.money.Currency.parse("BTC"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.UNSUPPORTED_CURRENCY));
    }
}
