package dev.ledgerforge.transaction;

import dev.ledgerforge.common.security.AuthenticatedUser;
import dev.ledgerforge.common.web.PageParams;
import dev.ledgerforge.common.web.PageResponse;
import dev.ledgerforge.transaction.TransactionDtos.LedgerView;
import dev.ledgerforge.transaction.TransactionDtos.TransactionDetail;
import dev.ledgerforge.transaction.TransactionDtos.TransactionSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transactions")
@Tag(name = "Transactions", description = "Ledger-backed transaction history and the ledger inspector")
@SecurityRequirement(name = "bearer")
public class TransactionController {

    private final TransactionQueryService transactions;

    public TransactionController(TransactionQueryService transactions) {
        this.transactions = transactions;
    }

    @GetMapping
    @Operation(
            summary = "Transactions touching the caller's accounts",
            description = "Filter by type (TOP_UP, TRANSFER, PAYMENT, REFUND, PAYOUT, REVERSAL), currency, direction"
                    + " (IN/OUT), date range and free text; sort by createdAt or amount.")
    public PageResponse<TransactionSummary> list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return transactions.list(
                user,
                new TransactionQueryService.Filter(type, currency, direction, q, from, to, sort),
                PageParams.of(page, size));
    }

    @GetMapping("/{transactionId}")
    @Operation(summary = "Transaction detail")
    public TransactionDetail detail(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID transactionId) {
        return transactions.detail(user, transactionId);
    }

    @GetMapping("/{transactionId}/ledger")
    @Operation(summary = "Double-entry postings of a transaction with debit/credit totals")
    public LedgerView ledger(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID transactionId) {
        return transactions.ledger(user, transactionId);
    }
}
