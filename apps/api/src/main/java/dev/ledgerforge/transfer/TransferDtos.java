package dev.ledgerforge.transfer;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class TransferDtos {

    private TransferDtos() {}

    public record CreateTransferRequest(
            @NotBlank
            @Size(max = 254)
            @Schema(description = "Recipient email or wallet account number", example = "ravi@example.com")
            String recipient,

            @Positive @Schema(description = "Amount in minor units", example = "20000")
            long amountMinor,

            @NotBlank @Schema(example = "INR") String currency,

            @Size(max = 140) @Schema(example = "Dinner split")
            String description) {}

    public record RecipientLookupRequest(
            @NotBlank @Size(max = 254) String recipient) {}

    public record RecipientView(String displayName, String maskedEmail, String accountNumberMasked, String currency) {}

    public record TransferView(
            UUID id,
            String reference,
            String direction,
            String counterpartyName,
            long amountMinor,
            String currency,
            String description,
            String status,
            UUID ledgerTransactionId,
            Long senderBalanceAfterMinor,
            Instant createdAt) {}
}
