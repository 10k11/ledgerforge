package dev.ledgerforge.merchant;

import dev.ledgerforge.common.security.AuthenticatedUser;
import dev.ledgerforge.common.web.PageParams;
import dev.ledgerforge.common.web.PageResponse;
import dev.ledgerforge.idempotency.IdempotentResponses;
import dev.ledgerforge.payment.PaymentDtos.PaymentIntentView;
import dev.ledgerforge.payment.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Merchant", description = "Merchant dashboard, payments, reporting and payouts")
@SecurityRequirement(name = "bearer")
public class MerchantController {

    private final MerchantService merchants;
    private final PaymentService payments;

    public MerchantController(MerchantService merchants, PaymentService payments) {
        this.merchants = merchants;
        this.payments = payments;
    }

    @GetMapping("/api/v1/merchants")
    @Operation(summary = "Directory of active merchants a customer can pay")
    public List<MerchantDtos.DirectoryEntry> directory(@RequestParam(required = false) String q) {
        return merchants.directory(q);
    }

    @GetMapping("/api/v1/merchant/dashboard")
    @Operation(summary = "Merchant summary metrics and 30-day activity")
    public MerchantDtos.Dashboard dashboard(@AuthenticationPrincipal AuthenticatedUser user) {
        return merchants.dashboard(user);
    }

    @GetMapping("/api/v1/merchant/balance")
    @Operation(summary = "Merchant payable balance")
    public MerchantDtos.BalanceView balance(@AuthenticationPrincipal AuthenticatedUser user) {
        return merchants.balance(user);
    }

    @GetMapping("/api/v1/merchant/payments")
    @Operation(summary = "Payments received by this merchant")
    public PageResponse<PaymentIntentView> payments(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return payments.list(
                user, new PaymentService.PaymentFilter(status, null, q, from, to, sort), PageParams.of(page, size));
    }

    @GetMapping("/api/v1/merchant/reports")
    @Operation(summary = "Daily gross, fee, net and refund totals for a date range (UTC days)")
    public MerchantDtos.Report reports(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return merchants.report(user, from, to);
    }

    @PostMapping("/api/v1/merchant/payouts")
    @Operation(
            summary = "Pay out available balance to the linked bank account (sandbox)",
            description = "Requires Idempotency-Key.")
    public ResponseEntity<MerchantDtos.PayoutView> payout(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody MerchantDtos.PayoutRequest request) {
        return IdempotentResponses.of(merchants.payout(user, idempotencyKey, request));
    }

    @GetMapping("/api/v1/merchant/payouts")
    @Operation(summary = "Payout history")
    public PageResponse<MerchantDtos.PayoutView> payouts(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return merchants.payouts(user, PageParams.of(page, size));
    }

    @GetMapping("/api/v1/merchant/profile")
    @Operation(summary = "Merchant business profile")
    public MerchantDtos.ProfileView profile(@AuthenticationPrincipal AuthenticatedUser user) {
        return merchants.profile(user);
    }

    @PatchMapping("/api/v1/merchant/profile")
    @Operation(summary = "Update merchant business profile")
    public MerchantDtos.ProfileView updateProfile(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody MerchantDtos.UpdateProfileRequest request) {
        return merchants.updateProfile(user, request);
    }
}
