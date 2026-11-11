package dev.ledgerforge.auth;

import org.springframework.http.ResponseCookie;

/**
 * Wraps a refresh failure so the advice can clear the (now useless) refresh cookie while still
 * rendering the original problem response.
 */
public class CookieClearingException extends RuntimeException {

    private final ResponseCookie clearCookie;

    public CookieClearingException(RuntimeException cause, ResponseCookie clearCookie) {
        super(cause);
        this.clearCookie = clearCookie;
    }

    public RuntimeException cause() {
        return (RuntimeException) getCause();
    }

    public ResponseCookie clearCookie() {
        return clearCookie;
    }
}
