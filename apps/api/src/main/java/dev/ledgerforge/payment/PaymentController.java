package dev.ledgerforge.payment;

import dev.ledgerforge.common.security.AuthenticatedUser;
import dev.ledgerforge.common.web.PageParams;
import dev.ledgerforge.common.web.PageResponse;
import dev.ledgerforge.idempotency.IdempotentResponses;
import dev.ledgerforge.payment.PaymentDtos.PaymentIntentView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payment-intents")
@Tag(name = "Payments", description = "Payment-intent lifecycle: create, confirm (capture), cancel")
@SecurityRequirement(name = "bearer")
public class PaymentController {

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping
    @Operation(
            summary = "Create a payment intent to a merchant (customer only)",
            description = "Fee is computed server-side and snapshotted. Requires Idempotency-Key.")
    public ResponseEntity<PaymentIntentView> create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentDtos.CreatePaymentIntentRequest request) {
        return IdempotentResponses.of(payments.create(user, idempotencyKey, request));
    }

    @PostMapping("/{paymentId}/confirm")
    @Operation(
            summary = "Confirm and capture a payment intent",
            description = "Posts balanced ledger entries and moves the intent to SUCCEEDED, or to FAILED when the"
                    + " sandbox processor declines. Requires Idempotency-Key; a payment can be captured at most once.")
    public ResponseEntity<PaymentIntentView> confirm(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID paymentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return IdempotentResponses.of(payments.confirm(user, paymentId, idempotencyKey));
    }

    @PostMapping("/{paymentId}/cancel")
    @Operation(summary = "Cancel a payment intent that has not been confirmed")
    public PaymentIntentView cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID paymentId) {
        return payments.cancel(user, paymentId);
    }

    @GetMapping
    @Operation(
            summary = "List payments visible to the caller",
            description = "Customers see their own payments, merchants payments made to them, admins all payments.")
    public PageResponse<PaymentIntentView> list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return payments.list(
                user, new PaymentService.PaymentFilter(status, currency, q, from, to, sort), PageParams.of(page, size));
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Get a payment intent")
    public PaymentIntentView get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID paymentId) {
        return payments.get(user, paymentId);
    }
}
