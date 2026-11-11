package dev.ledgerforge.auth;

import dev.ledgerforge.common.config.AppProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The refresh token travels only in an HttpOnly, SameSite=Strict cookie scoped to the auth path,
 * so page JavaScript can never read it and it is not sent to any other endpoint.
 */
@Component
public class RefreshCookieService {

    public static final String COOKIE_NAME = "lf_refresh";
    private static final String PATH = "/api/v1/auth";

    private final boolean secure;
    private final Duration ttl;

    public RefreshCookieService(AppProperties props) {
        this.secure = props.security().cookieSecure();
        this.ttl = props.security().refreshTokenTtl();
    }

    public ResponseCookie create(String token) {
        return ResponseCookie.from(COOKIE_NAME, token)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(PATH)
                .maxAge(ttl)
                .build();
    }

    public ResponseCookie clear() {
        return ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(PATH)
                .maxAge(0)
                .build();
    }

    public Optional<String> read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(c -> COOKIE_NAME.equals(c.getName()))
                .map(Cookie::getValue)
                .filter(v -> v != null && !v.isBlank() && v.length() <= 100)
                .findFirst();
    }
}
