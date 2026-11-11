package dev.ledgerforge.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Strongly typed application configuration bound from {@code app.*}. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String env,
        String frontendOrigin,
        String selfBaseUrl,
        Security security,
        Sandbox sandbox,
        Payments payments,
        Idempotency idempotency,
        RateLimit rateLimit,
        Webhooks webhooks,
        Seed seed,
        BootstrapAdmin bootstrapAdmin) {

    public boolean isProduction() {
        return "production".equalsIgnoreCase(env) || "prod".equalsIgnoreCase(env);
    }

    public record Security(
            Jwt jwt,
            Duration refreshTokenTtl,
            boolean cookieSecure,
            int bcryptStrength,
            int maxFailedLogins,
            Duration lockoutDuration,
            boolean merchantSelfSignup) {}

    public record Jwt(String secret, String issuer, String audience, Duration accessTokenTtl) {}

    public record Sandbox(boolean enabled, long maxTopUpMinor, long dailyTopUpLimitMinor) {}

    public record Payments(int platformFeeBps, long maxAmountMinor) {}

    public record Idempotency(Duration ttl, Duration cleanupInterval) {}

    public record RateLimit(boolean enabled, int authPerMinute, int moneyPerMinute) {}

    public record Webhooks(
            boolean workersEnabled,
            boolean allowPrivateTargets,
            boolean allowPlainHttp,
            String encryptionKey,
            int maxAttempts,
            int maxManualReplays,
            Duration baseBackoff,
            Duration maxBackoff,
            Duration connectTimeout,
            Duration requestTimeout,
            Duration leaseDuration,
            long pollIntervalMs,
            int batchSize,
            Duration signatureTolerance) {}

    public record Seed(boolean enabled) {}

    public record BootstrapAdmin(String email, String password) {}
}
