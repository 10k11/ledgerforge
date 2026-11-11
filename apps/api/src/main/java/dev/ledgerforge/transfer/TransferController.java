package dev.ledgerforge.transfer;

import dev.ledgerforge.common.security.AuthenticatedUser;
import dev.ledgerforge.common.web.PageParams;
import dev.ledgerforge.common.web.PageResponse;
import dev.ledgerforge.idempotency.IdempotentResponses;
import dev.ledgerforge.transfer.TransferDtos.TransferView;
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
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers", description = "Wallet-to-wallet transfers")
@SecurityRequirement(name = "bearer")
public class TransferController {

    private final TransferService transfers;

    public TransferController(TransferService transfers) {
        this.transfers = transfers;
    }

    @PostMapping
    @Operation(
            summary = "Send money to another customer",
            description = "Requires Idempotency-Key. Repeating the same key and body returns the original result"
                    + " (Idempotent-Replayed: true); the same key with a different body returns 409.")
    public ResponseEntity<TransferView> create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferDtos.CreateTransferRequest request) {
        return IdempotentResponses.of(transfers.create(user, idempotencyKey, request));
    }

    @PostMapping("/recipient-lookup")
    @Operation(summary = "Resolve a recipient for review before sending (masked details only)")
    public TransferDtos.RecipientView lookup(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody TransferDtos.RecipientLookupRequest request) {
        return transfers.lookupRecipient(user, request.recipient());
    }

    @GetMapping
    @Operation(summary = "List transfers sent or received")
    public PageResponse<TransferView> list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return transfers.list(user, direction, from, to, q, PageParams.of(page, size));
    }

    @GetMapping("/{transferId}")
    @Operation(summary = "Get one transfer you sent or received")
    public TransferView get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID transferId) {
        return transfers.get(user, transferId);
    }
}
