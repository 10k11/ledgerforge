package dev.ledgerforge.payment;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class PaymentDtos {

    private PaymentDtos() {}

    public record CreatePaymentIntentRequest(
            @NotNull @Schema(description = "Merchant user id from GET /api/v1/merchants")
            UUID merchantId,

            @Positive @Schema(description = "Gross amount in minor units", example = "100000")
            long amountMinor,

            @NotBlank @Schema(example = "INR") String currency,

            @Size(max = 255) @Schema(example = "Order #1042")
            String description,

            @Size(max = 10) Map<@Size(max = 40) String, @Size(max = 200) String> metadata) {}

    public record Party(UUID id, String name, String maskedEmail) {}

    public record PaymentIntentView(
            UUID id,
            String reference,
            String status,
            long amountMinor,
            String currency,
            int feeBps,
            long platformFeeMinor,
            long merchantNetMinor,
            long refundedAmountMinor,
            long refundableAmountMinor,
            String description,
            Map<String, String> metadata,
            Party merchant,
            Party customer,
            String failureCode,
            String failureMessage,
            UUID ledgerTransactionId,
            Instant createdAt,
            Instant updatedAt,
            Instant capturedAt,
            Instant cancelledAt) {}
}
