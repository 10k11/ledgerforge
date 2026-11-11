package dev.ledgerforge.common.web;

import dev.ledgerforge.common.config.AppProperties;
import dev.ledgerforge.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rate limits sensitive endpoints per client IP: authentication endpoints (brute-force and
 * credential-stuffing protection) and money-moving POST endpoints (abuse protection).
 * Account-level lockout in {@code AuthService} complements this per-IP limit.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class RateLimitFilter extends OncePerRequestFilter {

    private final boolean enabled;
    private final TokenBucketRateLimiter authLimiter;
    private final TokenBucketRateLimiter moneyLimiter;
    private final ProblemResponseWriter problems;

    public RateLimitFilter(AppProperties props, Clock clock, ProblemResponseWriter problems) {
        this.enabled = props.rateLimit().enabled();
        this.authLimiter = new TokenBucketRateLimiter(props.rateLimit().authPerMinute(), clock);
        this.moneyLimiter = new TokenBucketRateLimiter(props.rateLimit().moneyPerMinute(), clock);
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled || !"POST".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        String ip = ClientInfo.ipAddress(request);
        long retryAfter = 0;
        if (path.startsWith("/api/v1/auth/login")
                || path.startsWith("/api/v1/auth/register")
                || path.startsWith("/api/v1/auth/refresh")) {
            retryAfter = authLimiter.tryAcquire("auth:" + ip);
        } else if (isMoneyMoving(path)) {
            retryAfter = moneyLimiter.tryAcquire("money:" + ip);
        }
        if (retryAfter > 0) {
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            problems.write(
                    response,
                    ErrorCode.RATE_LIMITED,
                    "Too many requests; retry later",
                    Map.of("retryAfterSeconds", retryAfter));
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isMoneyMoving(String path) {
        return path.startsWith("/api/v1/wallet/sandbox/top-ups")
                || path.startsWith("/api/v1/transfers")
                || path.startsWith("/api/v1/payment-intents")
                || (path.startsWith("/api/v1/payments/") && path.endsWith("/refunds"))
                || path.startsWith("/api/v1/merchant/payouts");
    }
}
