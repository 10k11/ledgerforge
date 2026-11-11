package dev.ledgerforge.ledger;

import dev.ledgerforge.common.error.ApiException;
import dev.ledgerforge.common.util.ReferenceGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ledger engine: the only component that writes ledger entries or changes balances.
 *
 * <p>{@link #post} runs with {@link Propagation#MANDATORY}: it must join the caller's database
 * transaction so that the ledger postings, the balance projection and the caller's own state
 * (payment intent status, transfer record, outbox event, idempotency record) commit or roll back
 * together. A failure anywhere leaves no partial postings.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final LedgerAccountRepository accounts;
    private final AccountBalanceRepository balances;
    private final LedgerTransactionRepository transactions;
    private final LedgerEntryRepository entries;
    private final LedgerValidationService validation;
    private final Clock clock;

    public LedgerService(
            LedgerAccountRepository accounts,
            AccountBalanceRepository balances,
            LedgerTransactionRepository transactions,
            LedgerEntryRepository entries,
            LedgerValidationService validation,
            Clock clock) {
        this.accounts = accounts;
        this.balances = balances;
        this.transactions = transactions;
        this.entries = entries;
        this.validation = validation;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public PostedTransaction post(LedgerPostingRequest request) {
        // 1. Structural invariants (no I/O).
        validation.validateStructure(request);

        // 2. Accounts exist, are active and match the transaction currency.
        List<UUID> accountIds = request.postings().stream()
                .map(LedgerPostingRequest.Posting::accountId)
                .distinct()
                .toList();
        Map<UUID, LedgerAccount> accountMap = new HashMap<>();
        accounts.findAllById(accountIds).forEach(a -> accountMap.put(a.getId(), a));
        validation.validateAccounts(request, accountMap);

        // 3. Reversal invariants, serialised per original via a row lock on the original.
        if (request.reversesTransactionId() != null) {
            LedgerTransaction original = transactions
                    .lockById(request.reversesTransactionId())
                    .orElseThrow(() -> new LedgerInvariantViolation(
                            "REVERSAL_REFERENCE", "The original transaction does not exist"));
            validation.validateReversal(
                    request,
                    original,
                    entries.findByTransactionId(original.getId()),
                    entries.findReversalEntriesOf(original.getId()));
        }

        // 4. Lock balance rows in deterministic order, then apply and check every delta.
        Map<UUID, AccountBalance> locked = lockBalances(accountIds);
        Map<UUID, Long> deltas = new LinkedHashMap<>();
        for (LedgerPostingRequest.Posting p : request.postings()) {
            LedgerAccount account = accountMap.get(p.accountId());
            deltas.merge(
                    p.accountId(),
                    account.getAccountType().signedDelta(p.entryType(), p.amountMinor()),
                    Math::addExact);
        }
        Instant now = clock.instant();
        deltas.forEach((accountId, delta) -> {
            AccountBalance balance = locked.get(accountId);
            long resulting = Math.addExact(balance.getBalanceMinor(), delta);
            validation.validateResultingBalance(accountMap.get(accountId), resulting);
            balance.apply(delta, now);
        });

        // 5. Persist the immutable transaction and its entries.
        LedgerTransaction tx = new LedgerTransaction(
                UUID.randomUUID(),
                ReferenceGenerator.next("LTX"),
                request.type(),
                truncate(request.description(), 255),
                request.currency(),
                request.idempotencyKey(),
                request.relatedEntityId(),
                request.reversesTransactionId(),
                request.initiatedBy(),
                now);
        transactions.save(tx);
        List<LedgerEntry> written = new ArrayList<>();
        for (LedgerPostingRequest.Posting p : request.postings()) {
            written.add(new LedgerEntry(
                    UUID.randomUUID(), tx, p.accountId(), p.entryType(), p.amountMinor(), request.currency(), now));
        }
        entries.saveAll(written);

        log.info(
                "Posted ledger transaction ref={} type={} currency={} postings={}",
                tx.getReferenceNumber(),
                tx.getTransactionType(),
                tx.getCurrency(),
                written.size());
        Map<UUID, Long> resultingBalances = new HashMap<>();
        locked.forEach((id, b) -> resultingBalances.put(id, b.getBalanceMinor()));
        return new PostedTransaction(tx, written, resultingBalances);
    }

    /**
     * Acquires {@code SELECT ... FOR UPDATE} locks on the balance rows of the given accounts, one at
     * a time, in ascending account-id order. Every money movement acquires its locks in this same
     * global order, which rules out lock-order-inversion deadlocks between concurrent postings.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, AccountBalance> lockBalances(Collection<UUID> accountIds) {
        Map<UUID, AccountBalance> locked = new LinkedHashMap<>();
        for (UUID id : new TreeSet<>(accountIds)) {
            AccountBalance balance = balances.lockByLedgerAccountId(id)
                    .orElseThrow(() -> new LedgerInvariantViolation("ACCOUNT_EXISTS", "Balance row missing"));
            locked.put(id, balance);
        }
        return locked;
    }

    @Transactional(readOnly = true)
    public long balanceOf(UUID accountId) {
        return balances.findByLedgerAccountId(accountId)
                .map(AccountBalance::getBalanceMinor)
                .orElseThrow(() -> ApiException.notFound("Account balance"));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Result of a successful posting. */
    public record PostedTransaction(
            LedgerTransaction transaction, List<LedgerEntry> entries, Map<UUID, Long> resultingBalances) {

        public UUID id() {
            return transaction.getId();
        }

        public String reference() {
            return transaction.getReferenceNumber();
        }

        public long balanceAfter(UUID accountId) {
            return resultingBalances.get(accountId);
        }
    }
}
