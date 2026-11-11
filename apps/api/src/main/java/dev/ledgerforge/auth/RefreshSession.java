package dev.ledgerforge.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Server-side record of one refresh token. Only the SHA-256 hash of the token is stored. Each
 * rotation creates a new row in the same {@code familyId} and revokes the previous one; presenting
 * a revoked (already rotated) token signals theft and revokes the whole family.
 */
@Entity
@Table(name = "refresh_sessions")
public class RefreshSession {

    public static final String ROTATED = "ROTATED";
    public static final String LOGOUT = "LOGOUT";
    public static final String LOGOUT_ALL = "LOGOUT_ALL";
    public static final String REUSE_DETECTED = "REUSE_DETECTED";
    public static final String USER_REVOKED = "USER_REVOKED";
    public static final String PASSWORD_CHANGED = "PASSWORD_CHANGED";

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "family_id", nullable = false)
    private UUID familyId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", length = 32)
    private String revokedReason;

    @Column(name = "replaced_by")
    private UUID replacedBy;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    protected RefreshSession() {}

    public RefreshSession(
            UUID id,
            UUID userId,
            UUID familyId,
            String tokenHash,
            Instant now,
            Instant expiresAt,
            String userAgent,
            String ipAddress) {
        this.id = id;
        this.userId = userId;
        this.familyId = familyId;
        this.tokenHash = tokenHash;
        this.createdAt = now;
        this.expiresAt = expiresAt;
        this.lastUsedAt = now;
        this.userAgent = userAgent;
        this.ipAddress = ipAddress;
    }

    public boolean isActive(Instant now) {
        return revokedAt == null && expiresAt.isAfter(now);
    }

    public void revoke(String reason, Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
            revokedReason = reason;
        }
    }

    public void rotatedTo(UUID successor, Instant now) {
        revoke(ROTATED, now);
        replacedBy = successor;
        lastUsedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getFamilyId() {
        return familyId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getRevokedReason() {
        return revokedReason;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getIpAddress() {
        return ipAddress;
    }
}
