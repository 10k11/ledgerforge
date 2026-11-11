# Concurrency and Locking

Goal: concurrent requests can never overdraw an account, capture a payment twice, over-refund,
or leave the ledger unbalanced. Correctness relies on **PostgreSQL**, not on JVM synchronization,
so it holds across multiple application instances.

## 1. Transaction boundaries

Every money-moving service method is `@Transactional` (isolation **READ COMMITTED**, the PostgreSQL
default). Inside one database transaction it:

1. claims the idempotency key (`INSERT … ON CONFLICT DO NOTHING`);
2. locks the business row, if any (payment intent);
3. locks balance rows in deterministic order;
4. validates, posts entries, updates projections;
5. writes the domain record (transfer, refund, …), the outbox event and the audit record;
6. stores the idempotent response.

`LedgerService.post` uses `Propagation.MANDATORY`, so a posting can never run outside the caller's
transaction. Any exception rolls back all six steps. `LedgerEngineIT.failureAfterPostingInSameTransactionLeavesNoPartialPostings`
throws after a successful posting and asserts that nothing was persisted.

## 2. Why READ COMMITTED is enough

Balance checks never rely on a snapshot read. They read the balance row **after** taking
`SELECT … FOR UPDATE` on it, so the value is the latest committed one and cannot change until we
commit. Stronger isolation (SERIALIZABLE) would add retry-on-serialization-failure complexity
without adding safety for this access pattern. Reconciliation, which needs one consistent view
across many tables, runs at REPEATABLE READ.

## 3. Pessimistic row locks, in a global order

```java
// LedgerService
public Map<UUID, AccountBalance> lockBalances(Collection<UUID> accountIds) {
    Map<UUID, AccountBalance> locked = new LinkedHashMap<>();
    for (UUID id : new TreeSet<>(accountIds)) {          // ascending account id
        AccountBalance balance = balances.lockByLedgerAccountId(id)   // SELECT ... FOR UPDATE
                .orElseThrow(...);
        locked.put(id, balance);
    }
    return locked;
}
```

Deadlocks need a cycle: T1 holds A and wants B, while T2 holds B and wants A. Because every posting
acquires its balance locks one at a time in ascending account-id order, no cycle can form between
two postings. The overall lock order is:

```
payment_intents row (confirm / cancel / refund)  →  original ledger tx row (reversals)  →  balance rows (ascending id)
```

Every code path acquires locks in this order. Lock waits are bounded with a 5s
`jakarta.persistence.lock.timeout`, so a pathological wait becomes a retryable `409`, not a hung thread.

`ConcurrencyIT.randomConcurrentTransfersConserveMoneyAndNeverDeadlock` runs 60 simultaneous random
transfers among 5 users, including A→B and B→A at the same moment. Every response is either 201 or
422, the total money is conserved, and reconciliation is healthy.

## 4. Double-spending: the canonical scenario

> A user has ₹500. Two concurrent requests each try to send ₹400.

```
T1: lock wallet(A) ─ balance 500 ─ 500-400=100 ≥ 0 ✔ ─ post ─ COMMIT
T2:      lock wallet(A) … waits for T1 …          ─ balance 100 ─ 100-400 < 0 ✘ ─ 422 INSUFFICIENT_FUNDS ─ ROLLBACK
```

`ConcurrencyIT.twoConcurrentSpendsCannotOverdrawWallet` releases both requests through a start gate
and asserts:
* the statuses are exactly `{201, 422}`;
* the final balance is ₹100;
* the recipient has ₹400;
* there is one ledger transaction;
* reconciliation is healthy.

`manyConcurrentSpendsSucceedExactlyAsFarAsFundsAllow` scales this to 20 requests (5 succeed, 15 fail).

## 5. Other races and how each is closed

| Race | Mechanism | Test |
|---|---|---|
| Same request retried concurrently | idempotency claim blocks on the unique index | `concurrentIdenticalIdempotentRequestsCreateOneTransaction` |
| Same payment confirmed twice (different keys) | `SELECT … FOR UPDATE` on the intent + state machine | `concurrentConfirmationsCaptureAPaymentOnce` |
| Refunds racing past the refundable amount | intent row lock; ledger reversal invariant as a backstop | `concurrentRefundsNeverExceedCapturedAmount` |
| Daily top-up limit raced | balance rows locked *before* summing today's top-ups | `concurrentTopUpsRespectDailyLimit` |
| Refresh token used twice concurrently | `SELECT … FOR UPDATE` on the session row | `AuthIT.refreshRotatesTokenAndRequiresCsrf` |
| Two outbox dispatchers / delivery workers | `FOR UPDATE SKIP LOCKED` + lease | `WebhookIT` |
| Duplicate registration with the same email | unique index `ux_users_email`, mapped to 409 | `AuthIT.registrationValidation` |

## 6. Optimistic locking

`account_balances`, `payment_intents` and `users` also carry a JPA `@Version`. Under the
pessimistic locks above it never fires in normal operation. It is a safety net: if a future code
path updated these rows without taking the lock, the conflict would surface as `409
CONCURRENT_MODIFICATION` instead of a lost update.

## 7. Known trade-off: hot system accounts

Every top-up locks `SYS-FUNDING-INR`, and every payment locks `SYS-FEEREV-INR`. Under heavy load
these rows serialize their writers ("hot account" contention). This is fine for a sandbox. Options
for scale, in order of preference:
1. stop maintaining projections for system accounts (`allow_negative = true`) and compute them from entries;
2. shard each system account into N sub-accounts chosen by hash;
3. batch fee postings asynchronously.
