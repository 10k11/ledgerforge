package dev.ledgerforge.webhook;

import dev.ledgerforge.common.security.AuthenticatedUser;
import dev.ledgerforge.common.web.PageParams;
import dev.ledgerforge.common.web.PageResponse;
import dev.ledgerforge.webhook.WebhookDtos.DeliveryView;
import dev.ledgerforge.webhook.WebhookDtos.WebhookView;
import dev.ledgerforge.webhook.WebhookDtos.WebhookWithSecret;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/merchant/webhooks")
@Tag(name = "Webhooks", description = "Webhook endpoints, signed deliveries, retries and replay")
@SecurityRequirement(name = "bearer")
public class WebhookController {

    private final WebhookService webhooks;

    public WebhookController(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping
    @Operation(
            summary = "Register a webhook endpoint",
            description = "The URL is validated against SSRF rules. The signing secret is returned once, here.")
    public ResponseEntity<WebhookWithSecret> create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody WebhookDtos.CreateWebhookRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(webhooks.create(user, request));
    }

    @GetMapping
    @Operation(summary = "List webhook endpoints (secrets are never returned)")
    public List<WebhookView> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return webhooks.list(user);
    }

    @GetMapping("/event-types")
    @Operation(summary = "Event types available for subscription")
    public List<String> eventTypes() {
        return EventTypes.SUBSCRIBABLE;
    }

    @GetMapping("/sandbox-receipts")
    @Operation(summary = "Recent deliveries received by the built-in sandbox receiver")
    public List<WebhookDtos.ReceiptView> receipts(@AuthenticationPrincipal AuthenticatedUser user) {
        return webhooks.sandboxReceipts(user);
    }

    @PatchMapping("/{webhookId}")
    @Operation(summary = "Update URL, description, subscribed events or enabled flag")
    public WebhookView update(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID webhookId,
            @Valid @RequestBody WebhookDtos.UpdateWebhookRequest request) {
        return webhooks.update(user, webhookId, request);
    }

    @DeleteMapping("/{webhookId}")
    @Operation(summary = "Delete a webhook endpoint")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID webhookId) {
        webhooks.delete(user, webhookId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{webhookId}/rotate-secret")
    @Operation(summary = "Rotate the signing secret (returned once)")
    public WebhookWithSecret rotate(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID webhookId) {
        return webhooks.rotateSecret(user, webhookId);
    }

    @PostMapping("/{webhookId}/test")
    @Operation(summary = "Queue a webhook.test event for this endpoint")
    public ResponseEntity<DeliveryView> test(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID webhookId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(webhooks.sendTest(user, webhookId));
    }

    @GetMapping("/{webhookId}/deliveries")
    @Operation(summary = "Delivery history for an endpoint")
    public PageResponse<DeliveryView> deliveries(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID webhookId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return webhooks.deliveries(user, webhookId, status, PageParams.of(page, size));
    }

    @GetMapping("/{webhookId}/deliveries/{deliveryId}")
    @Operation(summary = "One delivery with its full attempt history")
    public DeliveryView delivery(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID webhookId,
            @PathVariable UUID deliveryId) {
        return webhooks.delivery(user, webhookId, deliveryId);
    }

    @PostMapping("/{webhookId}/replay")
    @Operation(summary = "Manually replay a delivery (bounded number of replays)")
    public ResponseEntity<DeliveryView> replay(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID webhookId,
            @Valid @RequestBody WebhookDtos.ReplayRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(webhooks.replay(user, webhookId, request.deliveryId()));
    }
}
