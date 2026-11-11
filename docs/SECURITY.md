# Security

LedgerForge is an educational sandbox: it never processes real money or collects card or bank
credentials. It is still built as if it did.

## 1. Authentication

| Concern | Implementation |
|---|---|
| Password storage | BCrypt, strength 12 (`BCRYPT_STRENGTH`); 10–64 characters, a letter and a digit, not the email, not a common password (`PasswordPolicy`) |
| Access token | HS256 JWT, **10 minutes**. Claims: `iss`, `aud`, `sub`, `exp`, `nbf`, `iat`, `jti`, `role`, `sid`. The decoder validates signature, expiry, issuer and audience (`JwtConfig`) |
| Where the access token lives | **Browser memory only** (`apps/web/src/lib/api.ts`). Never in localStorage or sessionStorage (a unit test asserts this) |
| Refresh token | 256-bit random opaque token in an `HttpOnly; SameSite=Strict; Path=/api/v1/auth` cookie, `Secure` in production. Only its **SHA-256 hash** is stored (`refresh_sessions.token_hash`) |
| Rotation | Every refresh issues a new token in the same session *family* and revokes the old one (`ROTATED`). The family keeps its original absolute expiry |
| Reuse detection | Presenting an already-rotated token revokes the **whole family** (`REUSE_DETECTED`) and is audited: a stolen token stops working for both parties |
| Immediate revocation | Each request checks that the token's session family is still active and the user enabled (`SessionAwareJwtConverter`). Logout, "sign out everywhere", session revoke and password change take effect on the **next request**, not at token expiry |
| Brute force | Per-IP token bucket on `/auth/login`, `/auth/register` and `/auth/refresh` (20/min); account lockout for 15 minutes after 5 failures (`423 ACCOUNT_LOCKED`) |
| User enumeration | Same error for unknown email and wrong password; a dummy BCrypt hash equalizes timing |

## 2. CSRF

Bearer-token endpoints are not CSRF-prone: browsers never attach an `Authorization` header on
their own. Only the two endpoints that authenticate via the refresh cookie, `POST /auth/refresh`
and `POST /auth/logout`, accept implicit credentials. They require **double-submit** CSRF
protection: Spring Security's `CookieCsrfTokenRepository` issues `XSRF-TOKEN`, and the request must
echo it in `X-XSRF-TOKEN`. The cookie is also `SameSite=Strict`. A refresh without the header gets
`403` (`AuthIT.refreshRotatesTokenAndRequiresCsrf`).

## 3. Authorization

Two layers, so that a mistake in one is caught by the other.

**URL rules** (`SecurityConfig`):
* `/api/v1/admin/**` and `/actuator/**` (except health and info) require **ADMIN**;
* `/api/v1/merchant/**` requires **MERCHANT**;
* `/api/v1/wallet/**`, `/api/v1/transfers/**` and payment-intent mutations require **CUSTOMER**.

**Ownership in services and queries** (IDOR protection):
* identity always comes from the verified token (`@AuthenticationPrincipal AuthenticatedUser`), never from request bodies;
* list queries put the ownership predicate *inside* the SQL (`customer_id = :me`, `merchant_id = :me`, `a.owner_id = :me`);
* single-resource reads load **and** filter by owner, returning **404 rather than 403**, so other users' ids cannot be probed;
* refunds require the payment's own merchant (or an admin);
* webhook endpoints and deliveries are always looked up by `(id, merchant_id)`.

Hidden UI buttons are a convenience only. The integration tests call the API directly as the wrong
user (`PaymentRefundIT.ownershipIsEnforcedOnPayments`, `WalletTransferIT.transferMovesMoneyBetweenWallets`,
`WebhookIT`, `AdminIT`).

## 4. Input handling

* Bean Validation on every request DTO; amounts are `long` minor units with `@Positive`.
* JSON floats are **rejected** (`accept-float-as-int: false`): `10.5` is a 400, never silently `10`.
* Request bodies are limited to 64 KB (`RequestSizeLimitFilter`, including chunked bodies).
* Server-side amount ceilings, currency allow-lists, and enum parsing with friendly errors.
* SQL: JPA/Criteria or parameterized `JdbcTemplate` everywhere. The few dynamically built SQL
  fragments (sort order, filters) come from fixed whitelists, never from user strings.
* XSS: React escapes output by default. The JSON payload viewer renders tokens as text nodes; a
  test asserts `<img onerror>` is rendered inert. No `dangerouslySetInnerHTML` anywhere.

## 5. Transport and headers

* **API:**
  * `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`;
  * `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`;
  * HSTS (over TLS);
  * `Cache-Control: no-store` on auth responses.
* **Web (nginx):**
  * a strict CSP (`script-src 'self'`, `connect-src 'self'`, `frame-ancestors 'none'`);
  * Permissions-Policy, nosniff, DENY.
* **CORS:** an explicit allow-list from `FRONTEND_ORIGIN`, credentials enabled, limited methods and
  headers. In Docker the SPA and API share one origin through the nginx proxy.

## 6. Errors and logging

* RFC 9457 problem responses with a stable `code` and a `correlationId`. Stack traces, SQL and class
  names are never returned (`server.error.include-stacktrace: never`, `GlobalExceptionHandler`).
* Every request gets an `X-Request-Id`, which is placed in the logging MDC and the audit log.
* Structured JSON logs (ECS) in Docker and production.
* Passwords, tokens, refresh hashes, webhook secrets and card-like data are never logged or audited.
  The audit tests assert the password does not appear.

## 7. Secrets and environments

* Secrets come only from environment variables or a git-ignored `.env`. `.env.example` holds placeholders.
* `ProductionSafetyGuard` **refuses to start**, before any database connection is opened, when `APP_ENV=production` and any of the following is true:
  * sandbox funding is on;
  * demo seeding is on;
  * private or HTTP webhook targets are allowed;
  * cookies are not `Secure`;
  * `WEBHOOK_ENCRYPTION_KEY` is missing;
  * `JWT_SECRET` is shorter than 32 bytes.

  The `prod` profile sets all of these safely anyway.
* Admins cannot self-register. They are created with `BOOTSTRAP_ADMIN_EMAIL` and
  `BOOTSTRAP_ADMIN_PASSWORD`, or by the dev seeder.

## 8. Webhook security

See `WEBHOOKS.md`: HMAC-SHA256 signatures with timestamp, constant-time verification, encrypted
secrets shown once, SSRF validation at registration and at send time, no redirects, timeouts, and
bounded retries and replays.

## 9. Rate limiting

An in-process token bucket (`TokenBucketRateLimiter`) limits auth endpoints per IP (20/min) and
money-moving POSTs per IP (60/min), returning `429` with `Retry-After`. **Limitation:** the state is
per instance. A multi-instance deployment should use a shared store such as Redis. The limiter
interface is deliberately small so that swap is easy. Redis is not required to run LedgerForge.

## 10. Known limitations

* The SSRF check has a narrow DNS time-of-check/time-of-use window (see `WEBHOOKS.md`).
* No email verification, password reset, or MFA. These are deliberately not faked in the UI.
* The client IP for rate limiting is the socket address. Behind a proxy, enable
  `server.forward-headers-strategy=native` and trust only the proxy.
