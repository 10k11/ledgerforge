package dev.ledgerforge.common.security;

import dev.ledgerforge.auth.RefreshSessionRepository;
import dev.ledgerforge.user.Role;
import dev.ledgerforge.user.User;
import dev.ledgerforge.user.UserRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Converts a cryptographically valid JWT into an {@link AuthenticatedUser}, additionally checking
 * server-side state: the token's session family must still be active and the user enabled. This
 * makes logout and session revocation effective immediately rather than at token expiry.
 */
@Component
public class SessionAwareJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final RefreshSessionRepository sessions;
    private final UserRepository users;
    private final Clock clock;

    public SessionAwareJwtConverter(RefreshSessionRepository sessions, UserRepository users, Clock clock) {
        this.sessions = sessions;
        this.users = users;
        this.clock = clock;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        UUID userId;
        UUID sessionId;
        Role role;
        try {
            userId = UUID.fromString(jwt.getSubject());
            sessionId = UUID.fromString(jwt.getClaimAsString("sid"));
            role = Role.valueOf(jwt.getClaimAsString("role"));
        } catch (RuntimeException e) {
            throw new BadCredentialsException("Malformed token claims");
        }
        if (!sessions.isFamilyActive(sessionId, userId, clock.instant())) {
            throw new BadCredentialsException("Session revoked");
        }
        User user = users.findById(userId).orElseThrow(() -> new BadCredentialsException("Unknown user"));
        if (user.getStatus() != User.Status.ACTIVE || user.getRole() != role) {
            throw new BadCredentialsException("User not active");
        }
        AuthenticatedUser principal = new AuthenticatedUser(userId, user.getEmail(), role, sessionId);
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority(role.authority())));
    }
}
