package dev.ledgerforge.wallet;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class WalletDtos {

    private WalletDtos() {}

    public record TopUpLimits(long maxPerTopUpMinor, long dailyLimitMinor, long usedTodayMinor) {}

    public record WalletView(
            UUID accountId,
            String accountNumber,
            String accountName,
            String currency,
            long balanceMinor,
            String status,
            boolean sandboxEnabled,
            TopUpLimits topUpLimits,
            Instant createdAt) {}

    public record BalanceView(String accountNumber, String currency, long balanceMinor, Instant asOf) {}

    public record TopUpRequest(
            @Positive @Schema(description = "Amount in minor units (paise/cents)", example = "100000")
            long amountMinor,

            @NotBlank @Schema(example = "INR") String currency) {}

    public record TopUpResponse(
            UUID transactionId,
            String reference,
            long amountMinor,
            String currency,
            long balanceAfterMinor,
            Instant createdAt) {}

    public record StatementLine(
            UUID transactionId,
            String reference,
            String type,
            String description,
            String direction,
            long amountMinor,
            long runningBalanceMinor,
            Instant postedAt) {}

    public record Statement(
            String accountNumber,
            String accountName,
            String currency,
            Instant from,
            Instant to,
            long openingBalanceMinor,
            long closingBalanceMinor,
            long totalCreditsMinor,
            long totalDebitsMinor,
            List<StatementLine> lines,
            int page,
            int size,
            long totalItems,
            int totalPages) {}
}
