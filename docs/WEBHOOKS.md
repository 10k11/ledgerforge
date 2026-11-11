# Webhooks and the Transactional Outbox

Code: `apps/api/src/main/java/dev/ledgerforge/webhook/`

## 1. Flow

```mermaid
sequenceDiagram
    participant API as PaymentService (tx)
    participant DB as PostgreSQL
    participant D as OutboxDispatcher
    participant W as DeliveryWorker
    participant M as Merchant endpoint
    API->>DB: ledger entries + intent SUCCEEDED + outbox_events row (one COMMIT)
    loop every second
      D->>DB: SELECT pending events FOR UPDATE SKIP LOCKED
      D->>DB: INSERT webhook_deliveries per subscribed endpoint (ON CONFLICT DO NOTHING)
      D->>DB: mark event PUBLISHED
    end
    loop every second
      W->>DB: claim due deliveries FOR UPDATE SKIP LOCKED, push next_attempt_at by a lease
      W->>W: re-validate URL (SSRF), sign payload
      W->>M: POST JSON (3s connect / 10s request timeout, no redirects)
      M-->>W: 2xx / error
      W->>DB: record attempt; SUCCEEDED, or RETRYING with backoff, or DEAD
    end
```

**Why an outbox?** Sending an HTTP request inside the payment transaction would couple capture
latency to merchant servers, and could notify a merchant about a payment that later rolled back.
Writing the event row in the same transaction guarantees an event exists **if and only if** the
change committed.

## 2. Events

`payment.created`, `payment.succeeded`, `payment.failed`, `payment.cancelled`, `payment.refunded`,
`payout.paid` (subscribable), and `webhook.test`. `transfer.completed` events are recorded in the
outbox for completeness but have no merchant audience.

Envelope (the outbox row's `id` is the stable event id):

```json
{ "id": "a1f0…", "type": "payment.succeeded", "createdAt": "2026-11-11T15:37:02Z", "livemode": false,
  "data": { "paymentId": "…", "reference": "PAY_…", "status": "SUCCEEDED", "amountMinor": 100000,
            "currency": "INR", "platformFeeMinor": 2000, "merchantNetMinor": 98000, "refundedAmountMinor": 0 } }
```

Payloads contain references and amounts only, never customer emails or names (asserted in `WebhookIT`).

## 3. Signatures

```
LedgerForge-Signature: t=1767225600,v1=<hex HMAC_SHA256(secret, t + "." + rawBody)>
LedgerForge-Event-Id:  <stable event id>
LedgerForge-Event-Type: payment.succeeded
LedgerForge-Timestamp: 1767225600
```

* The timestamp is inside the MAC, so receivers can reject replays (5-minute tolerance by default).
* `WebhookSignature.verify` is the reference receiver implementation: it parses the header, checks
  the tolerance, and compares with `MessageDigest.isEqual` (constant time). The Developers page
  shows the equivalent Node.js code.
* Secrets (`whsec_` + 256 random bits) are encrypted at rest with AES-256-GCM (`SecretCipher`,
  key from `WEBHOOK_ENCRYPTION_KEY`). They are returned **once**, at creation or rotation; afterwards
  only a hint (`whsec_…abcd`) is shown.

## 4. Retries and dead-lettering

* Any non-2xx response, timeout, connection error, or 3xx (redirects are never followed) is a failure.
* Backoff: `base × 2^(attempt−1)`, capped at `max-backoff` (10s → 1h by default), plus up to 10% jitter.
* After `max-attempts` (8) the delivery becomes **DEAD**. The merchant gets a notification, and the
  delivery is visible under the `DEAD` filter.
* **Manual replay** re-queues a `SUCCEEDED` or `DEAD` delivery with a fresh attempt budget. It is
  limited to `max-manual-replays` (5) per delivery, so replays cannot create unbounded loops.
* Every attempt is recorded in `webhook_delivery_attempts` (status, error, duration, 512-byte
  response snippet).

**At-least-once delivery.** If the worker crashes mid-request, the lease expires and the delivery
is retried. Receivers must de-duplicate on `LedgerForge-Event-Id`, which stays the same across
retries (asserted in `WebhookIT`).

Fan-out is idempotent too: the unique index `(event_id, endpoint_id)` means re-dispatching an event
never creates a second delivery to the same endpoint.

## 5. SSRF protection

`WebhookUrlValidator` runs **at registration and again before every delivery** (a DNS-rebinding
mitigation):

* `https` only (`http` allowed only with `WEBHOOK_ALLOW_PLAIN_HTTP=true`, which is impossible in production);
* no credentials in the URL, no fragment, at most 2048 characters;
* hostnames such as `localhost`, `*.local`, `*.internal`, `*.lan` are rejected;
* **every** resolved address must be public. These ranges are rejected:
  * loopback;
  * RFC 1918 private ranges;
  * link-local, including cloud metadata `169.254.169.254`;
  * CGNAT `100.64/10`;
  * `0/8`, multicast, and the TEST-NETs;
  * `240/4` (reserved);
  * IPv6 `::1`, `fc00::/7`, `fe80::/10`, `2001:db8::/32`;
  * IPv4-mapped IPv6 addresses (judged by the embedded IPv4).

Local development needs to reach `localhost`, so `WEBHOOK_ALLOW_PRIVATE_TARGETS=true` is set by the
`dev` profile only. `ProductionSafetyGuard` refuses to start if it is enabled in production.

**Residual risk (documented):** the address is validated and then resolved again by the HTTP client,
which leaves a narrow time-of-check/time-of-use window. Closing it fully means connecting to the
validated IP while preserving SNI/Host, or routing egress through a filtering proxy. Both are listed
under future work.

## 6. Built-in sandbox receiver

`POST /api/v1/sandbox/webhook-sink/{merchantId}` (sandbox only) verifies the signature against the
merchant's endpoint secrets exactly as a real receiver would, and records the receipt. Adding
`?fail=true` makes it return 503, which demonstrates retries and dead-lettering. The demo merchant is
pre-configured to deliver to it.
