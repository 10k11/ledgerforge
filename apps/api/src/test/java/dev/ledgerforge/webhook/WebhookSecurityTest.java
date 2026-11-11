package dev.ledgerforge.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerforge.common.error.ApiException;
import dev.ledgerforge.common.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WebhookSecurityTest {

    private static final String SECRET = "whsec_test_secret_value";
    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private static final Duration TOLERANCE = Duration.ofMinutes(5);

    @Test
    void signatureRoundTrips() {
        String body = "{\"id\":\"evt_1\",\"type\":\"payment.succeeded\"}";
        String header = WebhookSignature.sign(SECRET, NOW.getEpochSecond(), body);
        assertThat(header).startsWith("t=" + NOW.getEpochSecond() + ",v1=");
        assertThat(WebhookSignature.verify(SECRET, header, body, NOW, TOLERANCE))
                .isEqualTo(WebhookSignature.Result.VALID);
    }

    @Test
    void detectsTamperingWrongSecretAndReplay() {
        String body = "{\"amountMinor\":100}";
        String header = WebhookSignature.sign(SECRET, NOW.getEpochSecond(), body);
        assertThat(WebhookSignature.verify(SECRET, header, "{\"amountMinor\":999}", NOW, TOLERANCE))
                .isEqualTo(WebhookSignature.Result.SIGNATURE_MISMATCH);
        assertThat(WebhookSignature.verify("whsec_other", header, body, NOW, TOLERANCE))
                .isEqualTo(WebhookSignature.Result.SIGNATURE_MISMATCH);
        assertThat(WebhookSignature.verify(SECRET, header, body, NOW.plus(Duration.ofMinutes(6)), TOLERANCE))
                .isEqualTo(WebhookSignature.Result.TIMESTAMP_OUT_OF_TOLERANCE);
        // Timestamp swapped into the header without re-signing.
        String forged = header.replace("t=" + NOW.getEpochSecond(), "t=" + (NOW.getEpochSecond() + 1));
        assertThat(WebhookSignature.verify(SECRET, forged, body, NOW, TOLERANCE))
                .isEqualTo(WebhookSignature.Result.SIGNATURE_MISMATCH);
    }

    @Test
    void rejectsMalformedHeaders() {
        for (String h : new String[] {null, "", "v1=abc", "t=notanumber,v1=abc", "t=123"}) {
            assertThat(WebhookSignature.verify(SECRET, h, "{}", NOW, TOLERANCE))
                    .isEqualTo(WebhookSignature.Result.MALFORMED_HEADER);
        }
    }

    private final WebhookUrlValidator strict = new WebhookUrlValidator(false, false);

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://8.8.8.8/hook", // plain http
                "https://127.0.0.1/hook",
                "https://localhost/hook",
                "https://10.0.0.5/hook",
                "https://172.16.3.4/hook",
                "https://192.168.1.10/hook",
                "https://169.254.169.254/latest/meta-data", // cloud metadata
                "https://100.64.0.1/hook", // CGNAT
                "https://0.0.0.0/hook",
                "https://[::1]/hook",
                "https://[fd00::1]/hook", // unique local
                "https://[fe80::1]/hook", // link local
                "https://[::ffff:10.0.0.1]/hook", // IPv4-mapped private
                "https://user:pass@8.8.8.8/hook", // credentials
                "https://8.8.8.8/hook#frag",
                "ftp://8.8.8.8/file",
                "file:///etc/passwd",
                "https://metadata.internal/hook",
                "not a url"
            })
    void rejectsUnsafeWebhookTargets(String url) {
        assertThatThrownBy(() -> strict.validate(url))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.WEBHOOK_URL_REJECTED));
    }

    @Test
    void acceptsPublicHttpsTargets() {
        assertThatCode(() -> strict.validate("https://8.8.8.8/webhooks")).doesNotThrowAnyException();
        assertThatCode(() -> strict.validate("https://[2606:4700:4700::1111]/webhooks"))
                .doesNotThrowAnyException();
    }

    @Test
    void developmentFlagAllowsLocalTargets() {
        WebhookUrlValidator dev = new WebhookUrlValidator(true, true);
        assertThatCode(() -> dev.validate("http://localhost:8080/api/v1/sandbox/webhook-sink/x"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> dev.validate("file:///etc/passwd")).isInstanceOf(ApiException.class);
    }
}
