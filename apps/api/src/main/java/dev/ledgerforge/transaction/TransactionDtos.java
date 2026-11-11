package dev.ledgerforge.transaction;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class TransactionDtos {

    private TransactionDtos() {}

    /** One ledger transaction as seen by the viewer: amount is the net effect on the viewer's accounts. */
    public record TransactionSummary(
            UUID id,
            String reference,
            String type,
            String status,
            String description,
            String counterparty,
            String currency,
            long amountMinor,
            String direction,
            Instant createdAt) {}

    public record RelatedEntity(String kind, UUID id, String reference, String status) {}

    public record TransactionDetail(
            UUID id,
            String reference,
            String type,
            String status,
            String description,
            String counterparty,
            String currency,
            long amountMinor,
            String direction,
            Instant createdAt,
            Instant postedAt,
            String idempotencyKey,
            RelatedEntity related,
            UUID reversesTransactionId,
            String reversesReference,
            List<UUID> reversedBy) {}

    public record LedgerLine(
            UUID entryId,
            long sequenceNumber,
            String accountLabel,
            String accountNumber,
            String accountType,
            String entryType,
            long debitMinor,
            long creditMinor,
            boolean ownedByViewer) {}

    public record LedgerView(
            UUID transactionId,
            String reference,
            String currency,
            String status,
            List<LedgerLine> lines,
            long totalDebitsMinor,
            long totalCreditsMinor,
            boolean balanced,
            Instant postedAt) {}
}
