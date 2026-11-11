package dev.ledgerforge.merchant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MerchantDtos {

    private MerchantDtos() {}

    public record DirectoryEntry(UUID merchantId, String businessName, String category, List<String> currencies) {}

    public record BalanceView(
            UUID accountId, String accountNumber, String currency, long availableMinor, Instant asOf) {}

    public record DailyPoint(
            LocalDate date, long grossMinor, long feesMinor, long netMinor, long refundsMinor, long count) {}

    public record Dashboard(
            String businessName,
            String currency,
            long balanceMinor,
            long grossVolumeMinor,
            long platformFeesMinor,
            long netVolumeMinor,
            long refundedMinor,
            long paidOutMinor,
            long successfulPayments,
            long failedPayments,
            long pendingPayments,
            Map<String, Long> paymentsByStatus,
            List<DailyPoint> last30Days) {}

    public record Report(
            LocalDate from,
            LocalDate to,
            String currency,
            long grossVolumeMinor,
            long platformFeesMinor,
            long netVolumeMinor,
            long refundedMinor,
            long successfulPayments,
            List<DailyPoint> days) {}

    public record PayoutRequest(@Positive long amountMinor) {}

    public record PayoutView(
            UUID id,
            String reference,
            long amountMinor,
            String currency,
            String status,
            UUID ledgerTransactionId,
            long balanceAfterMinor,
            Instant createdAt) {}

    public record UpdateProfileRequest(
            @NotBlank @Size(max = 120) String businessName,
            @NotBlank @Size(max = 64) String category,
            @Email @Size(max = 254) String supportEmail,
            @Size(max = 255) String website) {}

    public record ProfileView(
            UUID id,
            String businessName,
            String category,
            String supportEmail,
            String website,
            String status,
            Instant createdAt) {}
}
