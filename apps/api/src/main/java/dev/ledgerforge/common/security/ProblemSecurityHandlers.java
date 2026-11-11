package dev.ledgerforge.common.security;

import dev.ledgerforge.common.error.ErrorCode;
import dev.ledgerforge.common.web.ProblemResponseWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

/** Renders 401/403 security failures as RFC 9457 problems, consistent with the rest of the API. */
@Component
public class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ProblemResponseWriter writer;

    public ProblemSecurityHandlers(ProblemResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        writer.write(response, ErrorCode.UNAUTHENTICATED, "A valid access token is required");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
            throws IOException {
        if (ex instanceof CsrfException) {
            writer.write(response, ErrorCode.FORBIDDEN, "Missing or invalid CSRF token");
            return;
        }
        writer.write(response, ErrorCode.FORBIDDEN, "You do not have access to this resource");
    }
}
