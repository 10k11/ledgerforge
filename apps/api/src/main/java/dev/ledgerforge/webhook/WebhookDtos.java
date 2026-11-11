package dev.ledgerforge.webhook;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class WebhookDtos {

    private WebhookDtos() {}

    public record CreateWebhookRequest(
            @NotBlank @Size(max = 2048) @Schema(example = "https://merchant.example.com/webhooks/ledgerforge")
            String url,

            @Size(max = 255) String description,
            @NotEmpty @Size(max = 20) List<@NotBlank String> events) {}

    public record UpdateWebhookRequest(
            @Size(max = 2048) String url,
            @Size(max = 255) String description,
            @Size(max = 20) List<String> events,
            Boolean enabled) {}

    public record ReplayRequest(@NotNull UUID deliveryId) {}

    public record DeliveryStats(long succeeded, long pending, long dead) {}

    public record WebhookView(
            UUID id,
            String url,
            String description,
            List<String> events,
            boolean enabled,
            String secretHint,
            DeliveryStats stats,
            Instant createdAt,
            Instant updatedAt) {}

    /** Returned only on creation and rotation: the full secret is never retrievable afterwards. */
    public record WebhookWithSecret(WebhookView webhook, String secret) {}

    public record AttemptView(
            int attemptNumber,
            Integer httpStatus,
            String error,
            long durationMs,
            String responseSnippet,
            Instant attemptedAt) {}

    public record DeliveryView(
            UUID id,
            UUID eventId,
            String eventType,
            String status,
            int attemptCount,
            int replayCount,
            Instant nextAttemptAt,
            Instant lastAttemptAt,
            Integer lastHttpStatus,
            String lastError,
            Instant deliveredAt,
            Instant createdAt,
            JsonNode payload,
            List<AttemptView> attempts) {}

    public record ReceiptView(UUID id, String eventId, String eventType, Boolean signatureValid, Instant receivedAt) {}
}
