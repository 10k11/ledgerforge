# Idempotency

Networks fail after the server commits but before the client hears back. Clients must be able to
retry a money-moving request without doing it twice.

Code: `apps/api/src/main/java/dev/ledgerforge/idempotency/IdempotencyService.java`

## 1. Contract

* Every money-moving `POST` requires an `Idempotency-Key` header (8–128 characters of
  `[A-Za-z0-9_-:.]`; a UUID is recommended). This covers top-ups, transfers, payment intent creation,
  confirmation, refunds, payouts, and admin reversals.
* Missing key → `400 IDEMPOTENCY_KEY_REQUIRED`. Malformed key → `400 IDEMPOTENCY_KEY_INVALID`.
* **Same key + same request** → the original status and body are returned, with
  `Idempotent-Replayed: true`. Nothing executes again.
* **Same key + different request** → `409 IDEMPOTENCY_KEY_REUSED`.
* Keys are scoped to **(authenticated user, HTTP method, endpoint path, key)**, so two users can
  never collide. The path includes resource ids (`/payment-intents/{id}/confirm`), so a key is
  bound to one resource.

## 2. Storage

Table `idempotency_records` (see `V1__core_schema.sql`):

| Column | Purpose |
|---|---|
| `owner_id, http_method, endpoint, idempotency_key` | the scope; **unique index** `ux_idem_scope` |
| `request_fingerprint` | SHA-256 of `METHOD \n ENDPOINT \n canonical-JSON(body)` |
| `response_status`, `response_body` (jsonb) | what to replay |
| `related_transaction_id` | the ledger transaction produced, for traceability |
| `created_at`, `expires_at` | retention (24h by default, `IDEMPOTENCY_TTL`) |

Canonical JSON sorts properties and map keys (`RequestFingerprinter`), so reordering JSON fields
does not change the fingerprint, while changing any value does.

## 3. Algorithm: claim inside the business transaction

```java
INSERT INTO idempotency_records (...) VALUES (...)
ON CONFLICT (owner_id, http_method, endpoint, idempotency_key) DO NOTHING
```

1. **Claim.** The insert runs in the *same* database transaction as the financial operation.
2. **Won the claim** (1 row inserted): run the operation, then store its response on the record.
   The record, ledger postings, and outbox event all commit together.
3. **Lost the claim** (0 rows): read the committed record and either replay it (fingerprint
   matches) or return 409.

**Concurrent duplicates.** If request B arrives while A's insert is still uncommitted, PostgreSQL
makes B's `INSERT … ON CONFLICT` **wait** on A's transaction. If A commits, B sees the conflict and
replays A's committed response. If A rolls back, B's insert succeeds and B executes. No in-memory
lock or polling is needed, and it works across many app instances. `ConcurrencyIT` fires 10
identical requests at once and asserts:
* one transfer was created;
* one ledger transaction exists for the key;
* one idempotency record exists;
* every response carries the same id;
* exactly one response is not a replay.

## 4. Failures are not cached

Because the record commits with the operation, a request that fails (validation, insufficient
funds, declined) leaves **no** record. The client can fix the cause (e.g. top up) and retry with
the same key. `WalletTransferIT.failedOperationDoesNotConsumeIdempotencyKey` covers this. A sandbox
*decline* is different: it is a successful API outcome (`200` with `status: FAILED`), so it *is*
recorded and replayed.

Trade-off: unlike some payment APIs, LedgerForge does not replay 4xx responses. The upside is that
a key never gets "stuck" on a transient business error. The downside is that a client could retry a
failed request with the same key and a different body, and it would execute. That is acceptable
because nothing had executed under that key.

## 5. Retention and expiry

Records expire after `IDEMPOTENCY_TTL` (24h), which is far longer than any sensible client retry
window. `IdempotencyCleanupJob` deletes expired rows in batches of 5,000 every 10 minutes. If an
expired record is encountered before cleanup, it is deleted and the key is claimed fresh. Ledger
transactions keep the key in their own `idempotency_key` column, so purging records never loses
history.

## 6. Defence in depth

Idempotency is not the only protection against double execution:
* payment capture is also guarded by the intent's **state machine** under a row lock (a second
  confirm with a *new* key gets `409 INVALID_STATE_TRANSITION`);
* refunds are bounded by the remaining refundable amount and by the ledger's reversal invariant.

## 7. Frontend usage

`useIdempotencyKey()` (`apps/web/src/hooks/index.ts`) creates one key per logical operation:
* the key stays the same across retries and double-clicks;
* it resets after a confirmed success or when the user edits the amount or recipient.

The review screens show the key so you can watch it being reused.
