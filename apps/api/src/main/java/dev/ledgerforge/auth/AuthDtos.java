package dev.ledgerforge.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.ledgerforge.user.Role;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {}

    public enum AccountType {
        CUSTOMER,
        MERCHANT
    }

    public record RegisterRequest(
            @NotBlank @Size(max = 120) @Schema(example = "Asha Verma")
            String fullName,

            @NotBlank @Email @Size(max = 254) @Schema(example = "asha@example.com")
            String email,

            @NotBlank @Size(min = 10, max = 64) @Schema(example = "correct-horse-42")
            String password,

            @NotNull @Schema(example = "CUSTOMER") AccountType accountType,

            @Pattern(regexp = "INR|USD", message = "must be INR or USD") @Schema(example = "INR")
            String currency,

            @Size(max = 120) @Schema(example = "Chai & Co") String businessName,

            @Size(max = 64) @Schema(example = "Food & Beverage")
            String category,

            @AssertTrue(message = "must be accepted") boolean acceptTerms) {

        @AssertTrue(message = "businessName is required for merchant accounts")
        @Schema(hidden = true)
        public boolean isBusinessNameValid() {
            return accountType != AccountType.MERCHANT || (businessName != null && !businessName.isBlank());
        }
    }

    public record LoginRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(max = 128) String password) {}

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 128) String currentPassword,
            @NotBlank @Size(min = 10, max = 64) String newPassword) {}

    public record UpdateProfileRequest(
            @NotBlank @Size(max = 120) String fullName) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MerchantInfo(UUID profileId, String businessName, String category, String supportEmail) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UserProfile(
            UUID id,
            String email,
            String fullName,
            Role role,
            String defaultCurrency,
            Instant createdAt,
            Instant lastLoginAt,
            MerchantInfo merchant) {}

    public record AuthResponse(
            String accessToken, String tokenType, long expiresInSeconds, Instant expiresAt, UserProfile user) {}

    public record SessionView(
            UUID id,
            Instant createdAt,
            Instant lastUsedAt,
            Instant expiresAt,
            String userAgent,
            String ipAddress,
            boolean current) {}

    public record CsrfResponse(String headerName, String token) {}
}
