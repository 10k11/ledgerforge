package dev.ledgerforge.refund;

import dev.ledgerforge.common.security.AuthenticatedUser;
import dev.ledgerforge.idempotency.IdempotentResponses;
import dev.ledgerforge.refund.RefundService.RefundView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments/{paymentId}/refunds")
@Tag(name = "Refunds", description = "Full and partial refunds of captured payments")
@SecurityRequirement(name = "bearer")
public class RefundController {

    private final RefundService refunds;

    public RefundController(RefundService refunds) {
        this.refunds = refunds;
    }

    @PostMapping
    @Operation(
            summary = "Refund a captured payment (merchant of the payment or admin)",
            description = "Omit amountMinor for a full refund of the remaining amount. Requires Idempotency-Key.")
    public ResponseEntity<RefundView> create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID paymentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RefundService.CreateRefundRequest request) {
        return IdempotentResponses.of(refunds.create(user, paymentId, idempotencyKey, request));
    }

    @GetMapping
    @Operation(summary = "List refunds of a payment")
    public List<RefundView> list(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID paymentId) {
        return refunds.list(user, paymentId);
    }
}
