package dev.ledgerforge.auth;

import dev.ledgerforge.common.config.AppProperties;
import dev.ledgerforge.user.User;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Issues short-lived access JWTs and high-entropy opaque refresh tokens. */
@Service
public class TokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JwtEncoder encoder;
    private final AppProperties.Jwt jwt;
    private final Clock clock;

    public TokenService(JwtEncoder encoder, AppProperties props, Clock clock) {
        this.encoder = encoder;
        this.jwt = props.security().jwt();
        this.clock = clock;
    }

    public record AccessToken(String value, Instant expiresAt) {}

    public AccessToken issueAccessToken(User user, UUID sessionFamilyId) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(jwt.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwt.issuer())
                .audience(List.of(jwt.audience()))
                .subject(user.getId().toString())
                .issuedAt(now)
                .notBefore(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("role", user.getRole().name())
                .claim("sid", sessionFamilyId.toString())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(token, expiresAt);
    }

    /** 256 bits of randomness, URL-safe. Only its SHA-256 hash is ever persisted. */
    public String newRefreshToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public Duration accessTokenTtl() {
        return jwt.accessTokenTtl();
    }
}
