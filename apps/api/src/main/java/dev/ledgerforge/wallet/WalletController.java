package dev.ledgerforge.wallet;

import dev.ledgerforge.common.security.AuthenticatedUser;
import dev.ledgerforge.common.web.PageParams;
import dev.ledgerforge.idempotency.IdempotentResponses;
import dev.ledgerforge.wallet.WalletDtos.TopUpResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/wallet")
@Tag(name = "Wallet", description = "Customer wallet, sandbox funding and statements")
@SecurityRequirement(name = "bearer")
public class WalletController {

    private final WalletService wallets;

    public WalletController(WalletService wallets) {
        this.wallets = wallets;
    }

    @GetMapping
    @Operation(summary = "Wallet overview")
    public WalletDtos.WalletView wallet(@AuthenticationPrincipal AuthenticatedUser user) {
        return wallets.wallet(user);
    }

    @GetMapping("/balance")
    @Operation(summary = "Current wallet balance")
    public WalletDtos.BalanceView balance(@AuthenticationPrincipal AuthenticatedUser user) {
        return wallets.balance(user);
    }

    @PostMapping("/sandbox/top-ups")
    @Operation(
            summary = "Add simulated funds (sandbox only)",
            description = "Posts Debit Platform Funding Clearing / Credit Customer Wallet. Requires Idempotency-Key."
                    + " Returns 403 SANDBOX_DISABLED in production.")
    public ResponseEntity<TopUpResponse> topUp(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Parameter(description = "Unique key per logical top-up (UUID recommended)")
                    @RequestHeader("Idempotency-Key")
                    String idempotencyKey,
            @Valid @RequestBody WalletDtos.TopUpRequest request) {
        return IdempotentResponses.of(wallets.topUp(user, idempotencyKey, request));
    }

    @GetMapping("/statement")
    @Operation(summary = "Wallet statement with opening/closing and running balances")
    public WalletDtos.Statement statement(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return wallets.statement(user, from, to, PageParams.of(page, size));
    }
}
