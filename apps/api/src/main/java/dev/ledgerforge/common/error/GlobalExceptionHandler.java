package dev.ledgerforge.common.error;

import dev.ledgerforge.common.web.CorrelationIdFilter;
import jakarta.persistence.OptimisticLockException;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Centralised translation of exceptions into RFC 9457 problem responses. Internal details (SQL,
 * stack traces, class names) are logged server-side with the correlation id but never returned.
 */
@RestControllerAdvice
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        if (ex.code().status().is5xxServerError()) {
            log.error("API error {}", ex.code(), ex);
        } else {
            log.debug("API error {}: {}", ex.code(), ex.getMessage());
        }
        return build(ex.code(), ex.getMessage(), ex.properties());
    }

    @ExceptionHandler(dev.ledgerforge.auth.CookieClearingException.class)
    ResponseEntity<ProblemDetail> handleCookieClearing(dev.ledgerforge.auth.CookieClearingException ex) {
        ResponseEntity<ProblemDetail> inner =
                ex.cause() instanceof ApiException api ? handleApi(api) : handleUnexpected(ex.cause());
        return ResponseEntity.status(inner.getStatusCode())
                .header(
                        org.springframework.http.HttpHeaders.SET_COOKIE,
                        ex.clearCookie().toString())
                .body(inner.getBody());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex) {
        List<Map<String, String>> errors = new ArrayList<>();
        ex.getBindingResult()
                .getFieldErrors()
                .forEach(fe -> errors.add(Map.of(
                        "field",
                        fe.getField(),
                        "message",
                        fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage())));
        ex.getBindingResult()
                .getGlobalErrors()
                .forEach(ge -> errors.add(Map.of(
                        "field",
                        ge.getObjectName(),
                        "message",
                        ge.getDefaultMessage() == null ? "invalid" : ge.getDefaultMessage())));
        return build(ErrorCode.VALIDATION_FAILED, "One or more fields are invalid", Map.of("errors", errors));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ProblemDetail> handleMethodValidation(HandlerMethodValidationException ex) {
        return build(ErrorCode.VALIDATION_FAILED, "One or more parameters are invalid", Map.of());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraint(ConstraintViolationException ex) {
        List<Map<String, String>> errors = ex.getConstraintViolations().stream()
                .map(v -> Map.of("field", v.getPropertyPath().toString(), "message", v.getMessage()))
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, "One or more parameters are invalid", Map.of("errors", errors));
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class,
        HttpMediaTypeNotSupportedException.class
    })
    ResponseEntity<ProblemDetail> handleMalformed(Exception ex) {
        return build(ErrorCode.MALFORMED_REQUEST, "The request could not be parsed", Map.of());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ProblemDetail> handleMissingHeader(MissingRequestHeaderException ex) {
        if ("Idempotency-Key".equalsIgnoreCase(ex.getHeaderName())) {
            return build(
                    ErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "This operation moves money and requires an Idempotency-Key header",
                    Map.of());
        }
        return build(ErrorCode.MALFORMED_REQUEST, "Missing required header " + ex.getHeaderName(), Map.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> handleMethod(HttpRequestMethodNotSupportedException ex) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed");
        decorate(pd, "METHOD_NOT_ALLOWED", "/problems/method-not-allowed");
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(pd);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ProblemDetail> handleNoResource(NoResourceFoundException ex) {
        return build(ErrorCode.NOT_FOUND, "No endpoint matches this path", Map.of());
    }

    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    ResponseEntity<ProblemDetail> handleDenied(Exception ex) {
        return build(ErrorCode.FORBIDDEN, "You do not have access to this resource", Map.of());
    }

    @ExceptionHandler({
        ObjectOptimisticLockingFailureException.class,
        OptimisticLockException.class,
        CannotAcquireLockException.class,
        PessimisticLockingFailureException.class
    })
    ResponseEntity<ProblemDetail> handleConcurrency(Exception ex) {
        log.warn("Concurrency conflict: {}", ex.getClass().getSimpleName());
        return build(
                ErrorCode.CONCURRENT_MODIFICATION,
                "The resource was modified concurrently; retry with the same Idempotency-Key",
                Map.of());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex) {
        String msg = String.valueOf(ex.getMostSpecificCause().getMessage());
        if (msg.contains("ck_account_balances_non_negative")) {
            return build(ErrorCode.INSUFFICIENT_FUNDS, "Insufficient funds for this operation", Map.of());
        }
        if (msg.contains("LEDGER_")) {
            log.error("Database rejected a ledger posting", ex);
            return build(ErrorCode.LEDGER_INVARIANT_VIOLATION, "The ledger rejected this posting", Map.of());
        }
        log.warn(
                "Data integrity violation: {}",
                ex.getMostSpecificCause().getClass().getSimpleName());
        return build(ErrorCode.CONCURRENT_MODIFICATION, "The request conflicts with the current state", Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return build(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred", Map.of());
    }

    public static ProblemDetail problem(ErrorCode code, String detail, Map<String, Object> properties) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(code.status(), detail);
        pd.setTitle(code.title());
        decorate(pd, code.name(), code.typeUri());
        properties.forEach(pd::setProperty);
        return pd;
    }

    private static void decorate(ProblemDetail pd, String code, String type) {
        pd.setType(URI.create(type));
        pd.setProperty("code", code);
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            pd.setProperty("correlationId", correlationId);
        }
    }

    private static ResponseEntity<ProblemDetail> build(ErrorCode code, String detail, Map<String, Object> props) {
        return ResponseEntity.status(code.status()).body(problem(code, detail, new LinkedHashMap<>(props)));
    }
}
