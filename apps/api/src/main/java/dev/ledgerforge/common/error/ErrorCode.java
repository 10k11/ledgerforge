package dev.ledgerforge.common.error;

import org.springframework.http.HttpStatus;

/** Stable, machine-readable error codes. Exposed as {@code code} and in the problem {@code type}. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed request"),
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Idempotency-Key header required"),
    IDEMPOTENCY_KEY_INVALID(HttpStatus.BAD_REQUEST, "Idempotency-Key header invalid"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid credentials"),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "Session expired"),
    ACCOUNT_LOCKED(HttpStatus.LOCKED, "Account temporarily locked"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    EMAIL_ALREADY_REGISTERED(HttpStatus.CONFLICT, "Email already registered"),
    IDEMPOTENCY_KEY_REUSED(HttpStatus.CONFLICT, "Idempotency key reused with a different request"),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "Invalid state transition"),
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "Concurrent modification"),
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_CONTENT, "Insufficient funds"),
    MERCHANT_INSUFFICIENT_BALANCE(HttpStatus.UNPROCESSABLE_CONTENT, "Merchant balance insufficient"),
    CURRENCY_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "Currency mismatch"),
    UNSUPPORTED_CURRENCY(HttpStatus.UNPROCESSABLE_CONTENT, "Unsupported currency"),
    SELF_TRANSFER(HttpStatus.UNPROCESSABLE_CONTENT, "Cannot transfer to yourself"),
    RECIPIENT_NOT_FOUND(HttpStatus.UNPROCESSABLE_CONTENT, "Recipient not found"),
    REFUND_EXCEEDS_REMAINING(HttpStatus.UNPROCESSABLE_CONTENT, "Refund exceeds refundable amount"),
    LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT, "Limit exceeded"),
    ACCOUNT_NOT_ACTIVE(HttpStatus.UNPROCESSABLE_CONTENT, "Account not active"),
    LEDGER_INVARIANT_VIOLATION(HttpStatus.UNPROCESSABLE_CONTENT, "Ledger invariant violation"),
    WEBHOOK_URL_REJECTED(HttpStatus.UNPROCESSABLE_CONTENT, "Webhook URL rejected"),
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_CONTENT, "Business rule violation"),
    SANDBOX_DISABLED(HttpStatus.FORBIDDEN, "Sandbox operations disabled"),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Payload too large"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Too many requests"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }

    public String typeUri() {
        return "/problems/" + name().toLowerCase().replace('_', '-');
    }
}
