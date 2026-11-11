package dev.ledgerforge.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "webhook_endpoints")
public class WebhookEndpoint {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false, length = 2048)
    private String url;

    @Column(length = 255)
    private String description;

    @Column(name = "secret_ciphertext", nullable = false, columnDefinition = "text")
    private String secretCiphertext;

    @Column(name = "secret_hint", nullable = false, length = 16)
    private String secretHint;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "subscribed_events", nullable = false, columnDefinition = "text[]")
    private String[] subscribedEvents;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected WebhookEndpoint() {}

    WebhookEndpoint(
            UUID id,
            UUID merchantId,
            String url,
            String description,
            String secretCiphertext,
            String secretHint,
            List<String> events,
            Instant now) {
        this.id = id;
        this.merchantId = merchantId;
        this.url = url;
        this.description = description;
        this.secretCiphertext = secretCiphertext;
        this.secretHint = secretHint;
        this.subscribedEvents = events.toArray(String[]::new);
        this.enabled = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void update(String url, String description, List<String> events, Boolean enabled, Instant now) {
        if (url != null) {
            this.url = url;
        }
        if (description != null) {
            this.description = description;
        }
        if (events != null) {
            this.subscribedEvents = events.toArray(String[]::new);
        }
        if (enabled != null) {
            this.enabled = enabled;
        }
        this.updatedAt = now;
    }

    void rotateSecret(String ciphertext, String hint, Instant now) {
        this.secretCiphertext = ciphertext;
        this.secretHint = hint;
        this.updatedAt = now;
    }

    void softDelete(Instant now) {
        this.deletedAt = now;
        this.enabled = false;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getUrl() {
        return url;
    }

    public String getDescription() {
        return description;
    }

    public String getSecretCiphertext() {
        return secretCiphertext;
    }

    public String getSecretHint() {
        return secretHint;
    }

    public List<String> getSubscribedEvents() {
        return List.of(subscribedEvents);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
