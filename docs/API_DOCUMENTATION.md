# API Documentation

* **Interactive:** `http://localhost:8080/swagger-ui.html` (disabled by default in production; `API_DOCS_ENABLED`).
* **OpenAPI 3 spec:** live at `/v3/api-docs`. A generated snapshot is committed at [`api/openapi.json`](api/openapi.json) (51 paths).

## Conventions

| Topic | Rule |
|---|---|
| Base path | `/api/v1` |
| Auth | `Authorization: Bearer <access token>` (10 min). Refresh with the HttpOnly cookie via `POST /auth/refresh` plus the `X-XSRF-TOKEN` header from `GET /auth/csrf` |
| Money | integers in minor units (`amountMinor`) with an explicit `currency` (`INR`, `USD`). Floats are rejected |
| Idempotency | `Idempotency-Key` is required on money-moving POSTs. Replays carry `Idempotent-Replayed: true`. See [IDEMPOTENCY.md](IDEMPOTENCY.md) |
| Pagination | `?page=0&size=20` (max 100). Response: `{ items, page, size, totalItems, totalPages }` |
| Dates | ISO-8601 instants (`from` inclusive, `to` exclusive) |
| Errors | `application/problem+json` (RFC 9457) with `code`, `detail`, `correlationId`, and `errors[]` for validation |
| Tracing | send or receive `X-Request-Id` |

## Error codes

| HTTP | `code` | Meaning |
|---|---|---|
| 400 | `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `IDEMPOTENCY_KEY_REQUIRED`, `IDEMPOTENCY_KEY_INVALID` | bad input |
| 401 | `UNAUTHENTICATED`, `INVALID_CREDENTIALS`, `INVALID_REFRESH_TOKEN` | not signed in or session invalid |
| 403 | `FORBIDDEN`, `SANDBOX_DISABLED` | role not allowed, missing CSRF token, or production mode |
| 404 | `NOT_FOUND` | missing **or not yours** (ownership is never revealed) |
| 409 | `IDEMPOTENCY_KEY_REUSED`, `INVALID_STATE_TRANSITION`, `EMAIL_ALREADY_REGISTERED`, `CONCURRENT_MODIFICATION` | conflicts |
| 422 | `INSUFFICIENT_FUNDS`, `MERCHANT_INSUFFICIENT_BALANCE`, `CURRENCY_MISMATCH`, `UNSUPPORTED_CURRENCY`, `SELF_TRANSFER`, `RECIPIENT_NOT_FOUND`, `REFUND_EXCEEDS_REMAINING`, `LIMIT_EXCEEDED`, `LEDGER_INVARIANT_VIOLATION`, `WEBHOOK_URL_REJECTED` | business rules |
| 423 | `ACCOUNT_LOCKED` | too many failed logins |
| 429 | `RATE_LIMITED` | see `Retry-After` |

Example:

```json
{
  "type": "/problems/insufficient-funds",
  "title": "Insufficient funds",
  "status": 422,
  "detail": "Insufficient funds for this operation",
  "instance": "/api/v1/transfers",
  "code": "INSUFFICIENT_FUNDS",
  "correlationId": "3b0f8a4e-5d1c-4c1e-9a43-6f0b2f1d7c55"
}
```

## Endpoints

### Auth: `/api/v1/auth`
| Method | Path | Notes |
|---|---|---|
| POST | `/register` | `{fullName, email, password, accountType: CUSTOMER\|MERCHANT, currency, businessName?, category?, acceptTerms}` → 201 plus the refresh cookie |
| POST | `/login` | `{email, password}` → `{accessToken, expiresInSeconds, user}` |
| GET | `/csrf` | issues `XSRF-TOKEN` |
| POST | `/refresh` | cookie + `X-XSRF-TOKEN`; rotates the refresh token |
| POST | `/logout` | cookie + `X-XSRF-TOKEN`; revokes the session family |
| POST | `/logout-all` | revokes every session |
| GET/PATCH | `/me` | profile |
| POST | `/password` | change password (signs out other sessions) |
| GET / DELETE | `/sessions`, `/sessions/{id}` | list / revoke sessions |

### Wallet (customer)
`GET /wallet` · `GET /wallet/balance` · `POST /wallet/sandbox/top-ups` (key) · `GET /wallet/statement?from&to&page&size`

### Transfers (customer)
`POST /transfers` (key) `{recipient (email or account number), amountMinor, currency, description?}` ·
`POST /transfers/recipient-lookup` · `GET /transfers?direction=ALL|SENT|RECEIVED&q` · `GET /transfers/{id}`

### Payments
`GET /merchants?q` (directory) ·
`POST /payment-intents` (customer, key) · `POST /payment-intents/{id}/confirm` (customer, key) ·
`POST /payment-intents/{id}/cancel` · `GET /payment-intents?status&currency&q&from&to&sort` · `GET /payment-intents/{id}`

Sandbox processor: amounts ending in `.13` are declined (`risk_declined`); amounts ending in `.14` fail with `processor_unavailable`.

### Refunds
`POST /payments/{id}/refunds` (merchant of the payment or admin, key) `{amountMinor?, reason?}`: omit `amountMinor` for the full remainder · `GET /payments/{id}/refunds`

### Transactions (ledger read model)
`GET /transactions?type&currency&direction=IN|OUT&q&from&to&sort` · `GET /transactions/{id}` · `GET /transactions/{id}/ledger`

### Merchant
`GET /merchant/dashboard` · `GET /merchant/balance` · `GET /merchant/payments` · `GET /merchant/reports?from&to` ·
`POST /merchant/payouts` (key) · `GET /merchant/payouts` · `GET|PATCH /merchant/profile`

### Webhooks (merchant): `/api/v1/merchant/webhooks`
`POST /` (secret returned once) · `GET /` · `GET /event-types` · `PATCH /{id}` · `DELETE /{id}` ·
`POST /{id}/rotate-secret` · `POST /{id}/test` · `GET /{id}/deliveries?status` · `GET /{id}/deliveries/{deliveryId}` ·
`POST /{id}/replay` `{deliveryId}` · `GET /sandbox-receipts`

### Other
`GET /dashboard/summary` · `GET /notifications` · `POST /notifications/read` · `GET /meta` (public)

### Admin: `/api/v1/admin`
`GET /reconciliation` · `GET /overview` · `GET /system-accounts` · `GET /audit-logs?action&actorId` · `GET /users?q` ·
`POST /ledger/transactions/{id}/reversal` (key) `{reason}`

### Operations
`GET /actuator/health`, `/actuator/health/readiness`, `/actuator/health/liveness` (public) · `/actuator/metrics` (admin)

## Example: a transfer with curl

```bash
TOKEN=$(curl -s localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"asha@ledgerforge.test","password":"Sandbox-Demo-2026"}' | jq -r .accessToken)

curl -i localhost:8080/api/v1/transfers \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"recipient":"ravi@ledgerforge.test","amountMinor":20000,"currency":"INR","description":"Lunch"}'
```
