package dev.ledgerforge.idempotency;

import dev.ledgerforge.common.error.ApiException;
import dev.ledgerforge.common.error.ErrorCode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Database-backed idempotency for money-moving operations.
 *
 * <p>Algorithm (runs inside the caller's transaction, {@link Propagation#MANDATORY}):
 *
 * <ol>
 *   <li>{@code INSERT ... ON CONFLICT DO NOTHING} a record for (owner, method, endpoint, key). If a
 *       concurrent request holds an uncommitted insert for the same scope, PostgreSQL blocks this
 *       statement until that transaction finishes; there is no window where both proceed.
 *   <li>If our insert won, execute the operation, then store its response on the record. Both
 *       commit atomically with the financial postings.
 *   <li>If the insert conflicted, read the committed record: same fingerprint &rarr; replay the
 *       stored response; different fingerprint &rarr; 409 Conflict.
 * </ol>
 *
 * <p>Because the record commits with the operation, a failed operation (validation error,
 * insufficient funds, ...) leaves no record and the client may retry with the same key once the
 * underlying problem is fixed. Successful results are replayed until {@code expires_at}.
 */
@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_\\-:.]{8,128}$");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final RequestFingerprinter fingerprinter;
    private final Clock clock;
    private final Duration ttl;

    public IdempotencyService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            RequestFingerprinter fingerprinter,
            Clock clock,
            dev.ledgerforge.common.config.AppProperties props) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.fingerprinter = fingerprinter;
        this.clock = clock;
        this.ttl = props.idempotency().ttl();
    }

    public record Scope(UUID ownerId, String httpMethod, String endpoint, String key) {}

    public record Result<T>(T body, int status, boolean replayed) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public <T> Result<T> execute(
            Scope scope,
            Object request,
            Class<T> responseType,
            int successStatus,
            Supplier<T> operation,
            Function<T, UUID> relatedTransactionId) {
        validateKey(scope.key());
        String fingerprint = fingerprinter.fingerprint(scope.httpMethod(), scope.endpoint(), request);
        Instant now = clock.instant();

        UUID recordId = UUID.randomUUID();
        if (!tryClaim(recordId, scope, fingerprint, successStatus, now)) {
            StoredRecord existing = load(scope);
            if (existing == null) {
                // The conflicting row vanished (expired and purged concurrently): claim again.
                if (!tryClaim(recordId, scope, fingerprint, successStatus, now)) {
                    throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION, "Retry the request");
                }
            } else if (existing.expiresAt().isBefore(now)) {
                jdbc.update("DELETE FROM idempotency_records WHERE id = ?", existing.id());
                if (!tryClaim(recordId, scope, fingerprint, successStatus, now)) {
                    throw new ApiException(ErrorCode.CONCURRENT_MODIFICATION, "Retry the request");
                }
            } else {
                return replay(existing, fingerprint, responseType);
            }
        }

        T response = operation.get();
        UUID related = relatedTransactionId == null ? null : relatedTransactionId.apply(response);
        jdbc.update(
                "UPDATE idempotency_records SET response_status = ?, response_body = ?::jsonb,"
                        + " related_transaction_id = ? WHERE id = ?",
                successStatus,
                objectMapper.writeValueAsString(response),
                related,
                recordId);
        return new Result<>(response, successStatus, false);
    }

    private boolean tryClaim(UUID id, Scope scope, String fingerprint, int status, Instant now) {
        int inserted = jdbc.update(
                """
                INSERT INTO idempotency_records
                    (id, owner_id, idempotency_key, http_method, endpoint, request_fingerprint,
                     response_status, response_body, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, '{}'::jsonb, ?, ?)
                ON CONFLICT (owner_id, http_method, endpoint, idempotency_key) DO NOTHING
                """,
                id,
                scope.ownerId(),
                scope.key(),
                scope.httpMethod(),
                scope.endpoint(),
                fingerprint,
                status,
                Timestamp.from(now),
                Timestamp.from(now.plus(ttl)));
        return inserted == 1;
    }

    private <T> Result<T> replay(StoredRecord existing, String fingerprint, Class<T> responseType) {
        if (!existing.fingerprint().equals(fingerprint)) {
            throw new ApiException(
                    ErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "This Idempotency-Key was already used with a different request body");
        }
        log.info("Idempotent replay of {} {}", existing.method(), existing.endpoint());
        T body = objectMapper.readValue(existing.body(), responseType);
        return new Result<>(body, existing.status(), true);
    }

    private StoredRecord load(Scope scope) {
        List<StoredRecord> rows = jdbc.query(
                """
                SELECT id, request_fingerprint, response_status, response_body::text, expires_at,
                       http_method, endpoint
                  FROM idempotency_records
                 WHERE owner_id = ? AND http_method = ? AND endpoint = ? AND idempotency_key = ?
                """,
                (rs, i) -> new StoredRecord(
                        rs.getObject(1, UUID.class),
                        rs.getString(2),
                        rs.getInt(3),
                        rs.getString(4),
                        rs.getTimestamp(5).toInstant(),
                        rs.getString(6),
                        rs.getString(7)),
                scope.ownerId(),
                scope.httpMethod(),
                scope.endpoint(),
                scope.key());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    static void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ApiException(
                    ErrorCode.IDEMPOTENCY_KEY_REQUIRED, "This operation requires an Idempotency-Key header");
        }
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new ApiException(
                    ErrorCode.IDEMPOTENCY_KEY_INVALID,
                    "Idempotency-Key must be 8-128 characters of [A-Za-z0-9_-:.] (a UUID is recommended)");
        }
    }

    private record StoredRecord(
            UUID id, String fingerprint, int status, String body, Instant expiresAt, String method, String endpoint) {}
}
