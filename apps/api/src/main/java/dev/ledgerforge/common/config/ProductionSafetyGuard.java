package dev.ledgerforge.common.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Fails application startup if a production deployment is configured to create simulated money,
 * seed demo credentials, or call private-network webhook targets. Production safety must never
 * depend on someone remembering to flip a flag.
 */
@Component
public class ProductionSafetyGuard {

    private static final int MIN_SECRET_BYTES = 32;
    private final AppProperties props;

    public ProductionSafetyGuard(AppProperties props) {
        this.props = props;
    }

    @PostConstruct
    void verify() {
        String secret = props.security().jwt().secret();
        if (secret == null || secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("JWT_SECRET must be set and at least 32 bytes long");
        }
        if (!props.isProduction()) {
            return;
        }
        if (props.sandbox().enabled()) {
            throw new IllegalStateException("Refusing to start: sandbox money creation is enabled in production");
        }
        if (props.seed().enabled()) {
            throw new IllegalStateException("Refusing to start: demo seed data is enabled in production");
        }
        if (props.webhooks().allowPrivateTargets() || props.webhooks().allowPlainHttp()) {
            throw new IllegalStateException("Refusing to start: insecure webhook targets are allowed in production");
        }
        if (!props.security().cookieSecure()) {
            throw new IllegalStateException("Refusing to start: refresh cookies must be Secure in production");
        }
        String key = props.webhooks().encryptionKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("Refusing to start: WEBHOOK_ENCRYPTION_KEY must be set in production");
        }
    }
}
