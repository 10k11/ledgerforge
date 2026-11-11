package dev.ledgerforge.ledger;

import dev.ledgerforge.common.money.Currency;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Instruction to post one balanced ledger transaction. Built by domain services (wallet, transfer,
 * payment, refund) and executed atomically by {@link LedgerService#post}.
 */
public record LedgerPostingRequest(
        LedgerTransactionType type,
        Currency currency,
        String description,
        List<Posting> postings,
        UUID relatedEntityId,
        UUID reversesTransactionId,
        String idempotencyKey,
        UUID initiatedBy) {

    public LedgerPostingRequest {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(description, "description");
        postings = List.copyOf(postings);
    }

    public record Posting(UUID accountId, EntryType entryType, long amountMinor) {
        public Posting {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(entryType, "entryType");
        }
    }

    public static Builder builder(LedgerTransactionType type, Currency currency) {
        return new Builder(type, currency);
    }

    public static final class Builder {
        private final LedgerTransactionType type;
        private final Currency currency;
        private final List<Posting> postings = new ArrayList<>();
        private String description;
        private UUID relatedEntityId;
        private UUID reversesTransactionId;
        private String idempotencyKey;
        private UUID initiatedBy;

        private Builder(LedgerTransactionType type, Currency currency) {
            this.type = type;
            this.currency = currency;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder debit(UUID accountId, long amountMinor) {
            postings.add(new Posting(accountId, EntryType.DEBIT, amountMinor));
            return this;
        }

        public Builder credit(UUID accountId, long amountMinor) {
            postings.add(new Posting(accountId, EntryType.CREDIT, amountMinor));
            return this;
        }

        public Builder relatedEntity(UUID relatedEntityId) {
            this.relatedEntityId = relatedEntityId;
            return this;
        }

        public Builder reverses(UUID originalTransactionId) {
            this.reversesTransactionId = originalTransactionId;
            return this;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        public Builder initiatedBy(UUID userId) {
            this.initiatedBy = userId;
            return this;
        }

        public LedgerPostingRequest build() {
            return new LedgerPostingRequest(
                    type,
                    currency,
                    description,
                    postings,
                    relatedEntityId,
                    reversesTransactionId,
                    idempotencyKey,
                    initiatedBy);
        }
    }
}
