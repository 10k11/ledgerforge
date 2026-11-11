package dev.ledgerforge.auth;

import dev.ledgerforge.auth.AuthDtos.AuthResponse;
import dev.ledgerforge.auth.AuthDtos.CsrfResponse;
import dev.ledgerforge.auth.AuthDtos.SessionView;
import dev.ledgerforge.auth.AuthDtos.UserProfile;
import dev.ledgerforge.common.security.AuthenticatedUser;
import dev.ledgerforge.common.web.ClientInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Registration, login, token refresh and session management")
public class AuthController {

    private final AuthService auth;
    private final RefreshCookieService cookies;

    public AuthController(AuthService auth, RefreshCookieService cookies) {
        this.auth = auth;
        this.cookies = cookies;
    }

    @PostMapping("/register")
    @Operation(
            summary = "Register a customer or merchant",
            description = "Creates the user and its ledger account(s),"
                    + " starts a session and sets the HttpOnly refresh cookie.")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody AuthDtos.RegisterRequest request, HttpServletRequest http) {
        return withSession(HttpStatus.CREATED, auth.register(request, client(http)));
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in with email and password")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody AuthDtos.LoginRequest request, HttpServletRequest http) {
        return withSession(HttpStatus.OK, auth.login(request, client(http)));
    }

    @PostMapping("/refresh")
    @Operation(
            summary = "Rotate the refresh token and issue a new access token",
            description = "Requires the lf_refresh cookie and the X-XSRF-TOKEN header (double-submit CSRF).")
    public ResponseEntity<AuthResponse> refresh(HttpServletRequest http) {
        try {
            return withSession(HttpStatus.OK, auth.refresh(cookies.read(http).orElse(null), client(http)));
        } catch (RuntimeException e) {
            throw new CookieClearingException(e, cookies.clear());
        }
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke the current session and clear the refresh cookie")
    public ResponseEntity<Void> logout(HttpServletRequest http) {
        auth.logout(cookies.read(http).orElse(null));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
                .build();
    }

    @GetMapping("/csrf")
    @Operation(summary = "Issue the XSRF-TOKEN cookie required by refresh/logout")
    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "Current user profile")
    public UserProfile me(@AuthenticationPrincipal AuthenticatedUser user) {
        return auth.me(user);
    }

    @PatchMapping("/me")
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "Update the current user's display name")
    public UserProfile updateMe(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody AuthDtos.UpdateProfileRequest request) {
        return auth.updateProfile(user, request);
    }

    @PostMapping("/password")
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "Change password; signs out all other sessions")
    public ResponseEntity<Void> changePassword(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody AuthDtos.ChangePasswordRequest request) {
        auth.changePassword(user, request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sessions")
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "List active sessions")
    public List<SessionView> sessions(@AuthenticationPrincipal AuthenticatedUser user) {
        return auth.sessions(user);
    }

    @DeleteMapping("/sessions/{sessionId}")
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "Revoke one of your sessions")
    public ResponseEntity<Void> revokeSession(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID sessionId) {
        auth.revokeSession(user, sessionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/logout-all")
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "Revoke every session of the current user")
    public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal AuthenticatedUser user) {
        auth.logoutAll(user);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
                .build();
    }

    private ResponseEntity<AuthResponse> withSession(HttpStatus status, AuthService.IssuedSession session) {
        return ResponseEntity.status(status)
                .header(
                        HttpHeaders.SET_COOKIE,
                        cookies.create(session.refreshToken()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(session.response());
    }

    private static AuthService.ClientContext client(HttpServletRequest http) {
        return new AuthService.ClientContext(ClientInfo.userAgent(http), ClientInfo.ipAddress(http));
    }
}
