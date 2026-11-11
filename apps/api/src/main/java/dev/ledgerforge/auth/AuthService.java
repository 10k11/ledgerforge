package dev.ledgerforge.auth;

import dev.ledgerforge.audit.AuditLog;
import dev.ledgerforge.audit.AuditService;
import dev.ledgerforge.auth.AuthDtos.AuthResponse;
import dev.ledgerforge.auth.AuthDtos.LoginRequest;
import dev.ledgerforge.auth.AuthDtos.MerchantInfo;
import dev.ledgerforge.auth.AuthDtos.RegisterRequest;
import dev.ledgerforge.auth.AuthDtos.SessionView;
import dev.ledgerforge.auth.AuthDtos.UserProfile;
import dev.ledgerforge.common.config.AppProperties;
import dev.ledgerforge.common.error.ApiException;
import dev.ledgerforge.common.error.ErrorCode;
import dev.ledgerforge.common.money.Currency;
import dev.ledgerforge.common.security.AuthenticatedUser;
import dev.ledgerforge.common.util.Hashing;
import dev.ledgerforge.merchant.MerchantProfileRepository;
import dev.ledgerforge.user.AccountProvisioningService;
import dev.ledgerforge.user.Role;
import dev.ledgerforge.user.User;
import dev.ledgerforge.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registration, login, refresh-token rotation, logout and session management.
 *
 * <p>Flows that must persist state <em>and then</em> fail (failed-login counters, refresh-token
 * reuse revocation) use explicit {@link TransactionTemplate} boundaries so the state change commits
 * before the error is returned.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final RefreshSessionRepository sessions;
    private final MerchantProfileRepository merchants;
    private final AccountProvisioningService provisioning;
    private final TokenService tokens;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final AppProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final String dummyHash;

    public AuthService(
            UserRepository users,
            RefreshSessionRepository sessions,
            MerchantProfileRepository merchants,
            AccountProvisioningService provisioning,
            TokenService tokens,
            PasswordEncoder passwordEncoder,
            AuditService audit,
            AppProperties props,
            Clock clock,
            PlatformTransactionManager txManager) {
        this.users = users;
        this.sessions = sessions;
        this.merchants = merchants;
        this.provisioning = provisioning;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        // Used to equalise response timing when the email does not exist.
        this.dummyHash = passwordEncoder.encode("timing-equaliser-" + UUID.randomUUID());
    }

    /** Client metadata recorded on sessions. */
    public record ClientContext(String userAgent, String ipAddress) {}

    /** Successful authentication: the JSON body plus the raw refresh token for the cookie. */
    public record IssuedSession(AuthResponse response, String refreshToken) {}

    // ------------------------------------------------------------------ register

    public IssuedSession register(RegisterRequest request, ClientContext client) {
        if (request.accountType() == AuthDtos.AccountType.MERCHANT
                && !props.security().merchantSelfSignup()) {
            throw ApiException.rule(
                    ErrorCode.BUSINESS_RULE_VIOLATION, "Merchant accounts are provisioned by an administrator");
        }
        PasswordPolicy.validate(request.password(), request.email(), "password");
        return tx.execute(status -> {
            User user = provisioning.provision(new AccountProvisioningService.NewUser(
                    request.email(),
                    request.fullName(),
                    request.password(),
                    request.accountType() == AuthDtos.AccountType.MERCHANT ? Role.MERCHANT : Role.CUSTOMER,
                    request.currency() == null ? Currency.INR : Currency.parse(request.currency()),
                    request.businessName(),
                    request.category()));
            user.recordSuccessfulLogin(clock.instant());
            audit.record(
                    user.getId(),
                    user.getRole().name(),
                    "USER_REGISTERED",
                    "USER",
                    user.getId(),
                    AuditLog.Result.SUCCESS,
                    Map.of("role", user.getRole().name()));
            return startSession(user, client);
        });
    }

    // ------------------------------------------------------------------ login

    private enum LoginOutcome {
        OK,
        BAD_CREDENTIALS,
        LOCKED,
        DISABLED
    }

    public IssuedSession login(LoginRequest request, ClientContext client) {
        String email = AccountProvisioningService.normaliseEmail(request.email());
        Optional<User> found = users.findByEmail(email);
        if (found.isEmpty()) {
            passwordEncoder.matches(request.password(), dummyHash);
            audit.recordIndependently(
                    null, null, "LOGIN", "USER", null, AuditLog.Result.FAILURE, Map.of("reason", "unknown_email"));
            throw invalidCredentials();
        }
        UUID userId = found.get().getId();
        boolean passwordOk =
                passwordEncoder.matches(request.password(), found.get().getPasswordHash());

        Object[] holder = new Object[1];
        LoginOutcome outcome = tx.execute(status -> {
            User user = users.findById(userId).orElseThrow();
            Instant now = clock.instant();
            if (user.getStatus() != User.Status.ACTIVE) {
                return LoginOutcome.DISABLED;
            }
            if (user.isLocked(now)) {
                return LoginOutcome.LOCKED;
            }
            if (!passwordOk) {
                user.recordFailedLogin(
                        props.security().maxFailedLogins(), props.security().lockoutDuration(), now);
                return LoginOutcome.BAD_CREDENTIALS;
            }
            user.recordSuccessfulLogin(now);
            audit.record(
                    user.getId(),
                    user.getRole().name(),
                    "LOGIN",
                    "USER",
                    user.getId(),
                    AuditLog.Result.SUCCESS,
                    Map.of());
            holder[0] = startSession(user, client);
            return LoginOutcome.OK;
        });
        switch (outcome) {
            case OK -> {
                return (IssuedSession) holder[0];
            }
            case LOCKED -> {
                audit.recordIndependently(
                        userId, null, "LOGIN", "USER", userId, AuditLog.Result.DENIED, Map.of("reason", "locked"));
                throw new ApiException(
                        ErrorCode.ACCOUNT_LOCKED, "Too many failed sign-in attempts. Try again in a few minutes.");
            }
            default -> {
                audit.recordIndependently(
                        userId,
                        null,
                        "LOGIN",
                        "USER",
                        userId,
                        AuditLog.Result.FAILURE,
                        Map.of("reason", outcome.name().toLowerCase()));
                throw invalidCredentials();
            }
        }
    }

    private static ApiException invalidCredentials() {
        return new ApiException(ErrorCode.INVALID_CREDENTIALS, "Email or password is incorrect");
    }

    // ------------------------------------------------------------------ refresh

    public IssuedSession refresh(String refreshToken, ClientContext client) {
        if (refreshToken == null) {
            throw new ApiException(ErrorCode.INVALID_REFRESH_TOKEN, "No active session");
        }
        String hash = Hashing.sha256Hex(refreshToken);
        boolean[] reuse = new boolean[1];
        IssuedSession issued = tx.execute(status -> {
            Instant now = clock.instant();
            RefreshSession current = sessions.lockByTokenHash(hash).orElse(null);
            if (current == null) {
                return null;
            }
            if (current.getRevokedAt() != null) {
                if (RefreshSession.ROTATED.equals(current.getRevokedReason())) {
                    // A rotated token was presented again: the token was likely stolen. Kill the family.
                    sessions.revokeFamily(current.getFamilyId(), RefreshSession.REUSE_DETECTED, now);
                    reuse[0] = true;
                    audit.record(
                            current.getUserId(),
                            null,
                            "REFRESH_TOKEN_REUSE",
                            "SESSION",
                            current.getFamilyId(),
                            AuditLog.Result.DENIED,
                            Map.of());
                    log.warn("Refresh token reuse detected; session family revoked");
                }
                return null;
            }
            if (!current.isActive(now)) {
                return null;
            }
            User user = users.findById(current.getUserId()).orElse(null);
            if (user == null || user.getStatus() != User.Status.ACTIVE) {
                return null;
            }
            String newToken = tokens.newRefreshToken();
            RefreshSession next = new RefreshSession(
                    UUID.randomUUID(),
                    user.getId(),
                    current.getFamilyId(),
                    Hashing.sha256Hex(newToken),
                    now,
                    current.getExpiresAt(), // absolute family lifetime: rotation does not extend it
                    client.userAgent(),
                    client.ipAddress());
            sessions.save(next);
            sessions.flush();
            current.rotatedTo(next.getId(), now);
            return new IssuedSession(authResponse(user, current.getFamilyId()), newToken);
        });
        if (issued == null) {
            throw new ApiException(
                    ErrorCode.INVALID_REFRESH_TOKEN,
                    reuse[0] ? "Session revoked for your protection. Please sign in again." : "Session expired");
        }
        return issued;
    }

    // ------------------------------------------------------------------ logout & sessions

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken == null) {
            return;
        }
        sessions.lockByTokenHash(Hashing.sha256Hex(refreshToken)).ifPresent(s -> {
            sessions.revokeFamily(s.getFamilyId(), RefreshSession.LOGOUT, clock.instant());
            audit.record(s.getUserId(), null, "LOGOUT", "SESSION", s.getFamilyId(), AuditLog.Result.SUCCESS, Map.of());
        });
    }

    @Transactional
    public int logoutAll(AuthenticatedUser user) {
        int revoked = sessions.revokeAllForUser(user.id(), RefreshSession.LOGOUT_ALL, clock.instant());
        audit.record(user, "LOGOUT_ALL", "USER", user.id(), AuditLog.Result.SUCCESS, Map.of("sessions", revoked));
        return revoked;
    }

    @Transactional(readOnly = true)
    public List<SessionView> sessions(AuthenticatedUser user) {
        return sessions.findActiveByUser(user.id(), clock.instant()).stream()
                .map(s -> new SessionView(
                        s.getFamilyId(),
                        s.getCreatedAt(),
                        s.getLastUsedAt(),
                        s.getExpiresAt(),
                        s.getUserAgent(),
                        s.getIpAddress(),
                        s.getFamilyId().equals(user.sessionId())))
                .toList();
    }

    @Transactional
    public void revokeSession(AuthenticatedUser user, UUID familyId) {
        boolean owned = sessions.findActiveByUser(user.id(), clock.instant()).stream()
                .anyMatch(s -> s.getFamilyId().equals(familyId));
        if (!owned) {
            throw ApiException.notFound("Session");
        }
        sessions.revokeFamily(familyId, RefreshSession.USER_REVOKED, clock.instant());
        audit.record(user, "SESSION_REVOKED", "SESSION", familyId, AuditLog.Result.SUCCESS, Map.of());
    }

    @Transactional
    public void changePassword(AuthenticatedUser principal, AuthDtos.ChangePasswordRequest request) {
        User user = users.findById(principal.id()).orElseThrow(() -> ApiException.notFound("User"));
        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Current password is incorrect");
        }
        PasswordPolicy.validate(request.newPassword(), user.getEmail(), "newPassword");
        Instant now = clock.instant();
        user.changePassword(passwordEncoder.encode(request.newPassword()), now);
        // Sign out every other device; keep the session that made the change.
        sessions.revokeAllForUserExcept(user.getId(), principal.sessionId(), RefreshSession.PASSWORD_CHANGED, now);
        audit.record(principal, "PASSWORD_CHANGED", "USER", user.getId(), AuditLog.Result.SUCCESS, Map.of());
    }

    @Transactional
    public UserProfile updateProfile(AuthenticatedUser principal, AuthDtos.UpdateProfileRequest request) {
        User user = users.findById(principal.id()).orElseThrow(() -> ApiException.notFound("User"));
        user.rename(request.fullName().trim(), clock.instant());
        audit.record(principal, "PROFILE_UPDATED", "USER", user.getId(), AuditLog.Result.SUCCESS, Map.of());
        return profile(user);
    }

    @Transactional(readOnly = true)
    public UserProfile me(AuthenticatedUser principal) {
        return profile(users.findById(principal.id()).orElseThrow(() -> ApiException.notFound("User")));
    }

    // ------------------------------------------------------------------ helpers

    private IssuedSession startSession(User user, ClientContext client) {
        Instant now = clock.instant();
        String refreshToken = tokens.newRefreshToken();
        UUID familyId = UUID.randomUUID();
        sessions.save(new RefreshSession(
                UUID.randomUUID(),
                user.getId(),
                familyId,
                Hashing.sha256Hex(refreshToken),
                now,
                now.plus(props.security().refreshTokenTtl()),
                client.userAgent(),
                client.ipAddress()));
        return new IssuedSession(authResponse(user, familyId), refreshToken);
    }

    private AuthResponse authResponse(User user, UUID familyId) {
        TokenService.AccessToken access = tokens.issueAccessToken(user, familyId);
        return new AuthResponse(
                access.value(), "Bearer", tokens.accessTokenTtl().toSeconds(), access.expiresAt(), profile(user));
    }

    UserProfile profile(User user) {
        MerchantInfo merchant = user.getRole() == Role.MERCHANT
                ? merchants
                        .findByUserId(user.getId())
                        .map(m ->
                                new MerchantInfo(m.getId(), m.getBusinessName(), m.getCategory(), m.getSupportEmail()))
                        .orElse(null)
                : null;
        return new UserProfile(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole(),
                user.getDefaultCurrency().name(),
                user.getCreatedAt(),
                user.getLastLoginAt(),
                merchant);
    }
}
