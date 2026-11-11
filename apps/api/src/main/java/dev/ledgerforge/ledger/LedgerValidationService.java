package dev.ledgerforge.ledger;

import dev.ledgerforge.common.error.ApiException;
import dev.ledgerforge.common.error.ErrorCode;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Pure (side-effect free) validation of ledger invariants. Every posting goes through these checks
 * before anything is written; the database triggers in {@code V1__core_schema.sql} re-check the
 * critical ones (balance, immutability, currency) as defence in depth.
 *
 * <p>Invariants:
 *
 * <ol>
 *   <li>{@code MIN_TWO_POSTINGS}: at least two postings.
 *   <li>{@code POSITIVE_AMOUNTS}: every amount is strictly positive.
 *   <li>{@code BALANCED}: total debits equal total credits.
 *   <li>{@code SINGLE_DIRECTION_PER_ACCOUNT}: an account is not both debited and credited.
 *   <li>{@code ACCOUNT_EXISTS} / {@code ACCOUNT_ACTIVE}: every account exists and is active.
 *   <li>{@code CURRENCY_MATCH}: every account's currency equals the transaction currency.
 *   <li>{@code REVERSAL_REFERENCE}: reversals (and only reversals) reference an original.
 *   <li>{@code REVERSAL_MIRRORS_ORIGINAL}: a reversal only posts opposite-direction amounts to
 *       accounts that the original touched.
 *   <li>{@code REVERSAL_NOT_EXCEEDED}: cumulative reversals never exceed the original per account.
 *   <li>{@code NON_NEGATIVE_BALANCE}: accounts that disallow negative balances stay &gt;= 0.
 * </ol>
 */
@Service
public class LedgerValidationService {

    public void validateStructure(LedgerPostingRequest request) {
        List<LedgerPostingRequest.Posting> postings = request.postings();
        if (postings.size() < 2) {
            throw new LedgerInvariantViolation("MIN_TWO_POSTINGS", "A ledger transaction needs at least two postings");
        }
        Map<EntryType, Long> totals = new EnumMap<>(EntryType.class);
        totals.put(EntryType.DEBIT, 0L);
        totals.put(EntryType.CREDIT, 0L);
        Map<UUID, EntryType> directionByAccount = new HashMap<>();
        for (LedgerPostingRequest.Posting p : postings) {
            if (p.amountMinor() <= 0) {
                throw new LedgerInvariantViolation("POSITIVE_AMOUNTS", "Posting amounts must be strictly positive");
            }
            totals.merge(p.entryType(), p.amountMinor(), Math::addExact);
            EntryType previous = directionByAccount.putIfAbsent(p.accountId(), p.entryType());
            if (previous != null && previous != p.entryType()) {
                throw new LedgerInvariantViolation(
                        "SINGLE_DIRECTION_PER_ACCOUNT", "An account cannot be debited and credited in one transaction");
            }
        }
        if (!totals.get(EntryType.DEBIT).equals(totals.get(EntryType.CREDIT))) {
            throw new LedgerInvariantViolation(
                    "BALANCED",
                    "Debits (" + totals.get(EntryType.DEBIT) + ") must equal credits (" + totals.get(EntryType.CREDIT)
                            + ")");
        }
        boolean isReversal = request.type().isReversal();
        if (isReversal != (request.reversesTransactionId() != null)) {
            throw new LedgerInvariantViolation(
                    "REVERSAL_REFERENCE",
                    isReversal
                            ? "A reversal must reference the original transaction"
                            : "Only reversals may reference another transaction");
        }
    }

    public void validateAccounts(LedgerPostingRequest request, Map<UUID, LedgerAccount> accounts) {
        for (LedgerPostingRequest.Posting p : request.postings()) {
            LedgerAccount account = accounts.get(p.accountId());
            if (account == null) {
                throw new LedgerInvariantViolation("ACCOUNT_EXISTS", "Ledger account does not exist");
            }
            if (!account.isActive()) {
                throw new ApiException(
                        ErrorCode.ACCOUNT_NOT_ACTIVE, "Account " + account.getAccountNumber() + " is not active");
            }
            if (account.getCurrency() != request.currency()) {
                throw new ApiException(
                        ErrorCode.CURRENCY_MISMATCH,
                        "Account currency " + account.getCurrency() + " does not match transaction currency "
                                + request.currency());
            }
        }
    }

    /**
     * Validates a reversal against its original transaction and all prior reversals of it.
     *
     * @param originalEntries entries of the original transaction
     * @param priorReversalEntries entries of reversals already posted against the original
     */
    public void validateReversal(
            LedgerPostingRequest request,
            LedgerTransaction original,
            List<LedgerEntry> originalEntries,
            List<LedgerEntry> priorReversalEntries) {
        if (original.getTransactionType().isReversal()) {
            throw new LedgerInvariantViolation("REVERSAL_REFERENCE", "A reversal cannot itself be reversed");
        }
        if (original.getCurrency() != request.currency()) {
            throw new ApiException(ErrorCode.CURRENCY_MISMATCH, "Reversal currency must match the original");
        }
        Map<UUID, LedgerEntry> originalByAccount = new HashMap<>();
        for (LedgerEntry e : originalEntries) {
            originalByAccount.put(e.getLedgerAccountId(), e);
        }
        Map<UUID, Long> alreadyReversed = new HashMap<>();
        for (LedgerEntry e : priorReversalEntries) {
            alreadyReversed.merge(e.getLedgerAccountId(), e.getAmountMinor(), Math::addExact);
        }
        Set<UUID> seen = new HashSet<>();
        for (LedgerPostingRequest.Posting p : request.postings()) {
            LedgerEntry originalEntry = originalByAccount.get(p.accountId());
            if (originalEntry == null
                    || originalEntry.getEntryType() != p.entryType().opposite()) {
                throw new LedgerInvariantViolation(
                        "REVERSAL_MIRRORS_ORIGINAL",
                        "A reversal may only post the opposite side of the original transaction's postings");
            }
            if (!seen.add(p.accountId())) {
                throw new LedgerInvariantViolation(
                        "REVERSAL_MIRRORS_ORIGINAL", "A reversal may post to each original account at most once");
            }
            long cumulative = Math.addExact(alreadyReversed.getOrDefault(p.accountId(), 0L), p.amountMinor());
            if (cumulative > originalEntry.getAmountMinor()) {
                throw new LedgerInvariantViolation(
                        "REVERSAL_NOT_EXCEEDED", "Reversal exceeds the unreversed amount of the original transaction");
            }
        }
    }

    /** Verifies the projected balance after applying a delta respects the account's floor. */
    public void validateResultingBalance(LedgerAccount account, long resultingBalance) {
        if (resultingBalance >= 0 || account.isAllowNegative()) {
            return;
        }
        if (account.getPurpose() == AccountPurpose.MERCHANT_PAYABLE) {
            throw new ApiException(
                    ErrorCode.MERCHANT_INSUFFICIENT_BALANCE, "The merchant balance is insufficient for this operation");
        }
        throw new ApiException(ErrorCode.INSUFFICIENT_FUNDS, "Insufficient funds for this operation");
    }
}
