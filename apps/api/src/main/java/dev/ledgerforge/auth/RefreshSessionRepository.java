package dev.ledgerforge.auth;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface RefreshSessionRepository extends JpaRepository<RefreshSession, UUID> {

    /** Locks the row so two concurrent refreshes of the same token cannot both rotate it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RefreshSession s where s.tokenHash = :tokenHash")
    Optional<RefreshSession> lockByTokenHash(String tokenHash);

    @Query("""
            select count(s) > 0 from RefreshSession s
            where s.familyId = :familyId and s.userId = :userId
              and s.revokedAt is null and s.expiresAt > :now
            """)
    boolean isFamilyActive(UUID familyId, UUID userId, Instant now);

    @Query("""
            select s from RefreshSession s
            where s.userId = :userId and s.revokedAt is null and s.expiresAt > :now
            order by s.lastUsedAt desc
            """)
    List<RefreshSession> findActiveByUser(UUID userId, Instant now);

    @Modifying
    @Query("""
            update RefreshSession s set s.revokedAt = :now, s.revokedReason = :reason
            where s.familyId = :familyId and s.revokedAt is null
            """)
    int revokeFamily(UUID familyId, String reason, Instant now);

    @Modifying
    @Query("""
            update RefreshSession s set s.revokedAt = :now, s.revokedReason = :reason
            where s.userId = :userId and s.revokedAt is null
            """)
    int revokeAllForUser(UUID userId, String reason, Instant now);

    @Modifying
    @Query("""
            update RefreshSession s set s.revokedAt = :now, s.revokedReason = :reason
            where s.userId = :userId and s.revokedAt is null and s.familyId <> :keepFamilyId
            """)
    int revokeAllForUserExcept(UUID userId, UUID keepFamilyId, String reason, Instant now);
}
