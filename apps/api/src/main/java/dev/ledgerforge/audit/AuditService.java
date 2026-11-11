package dev.ledgerforge.audit;

import dev.ledgerforge.common.security.AuthenticatedUser;
import dev.ledgerforge.common.web.ClientInfo;
import dev.ledgerforge.common.web.CorrelationIdFilter;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Append-only audit trail.
 *
 * <ul>
 *   <li>{@link #record} joins the caller's transaction: a success audit commits only if the
 *       audited operation commits.
 *   <li>{@link #recordIndependently} uses REQUIRES_NEW so failures and denials are recorded even
 *       though the business transaction rolls back.
 * </ul>
 *
 * Metadata must be minimal and never contain passwords, tokens or secrets.
 */
@Service
public class AuditService {

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AuditService(AuditLogRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public void record(
            AuthenticatedUser actor,
            String action,
            String resourceType,
            Object resourceId,
            AuditLog.Result result,
            Map<String, ?> metadata) {
        save(
                actor == null ? null : actor.id(),
                actor == null ? null : actor.role().name(),
                action,
                resourceType,
                resourceId,
                result,
                metadata);
    }

    @Transactional
    public void record(
            UUID actorId,
            String actorRole,
            String action,
            String resourceType,
            Object resourceId,
            AuditLog.Result result,
            Map<String, ?> metadata) {
        save(actorId, actorRole, action, resourceType, resourceId, result, metadata);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependently(
            UUID actorId,
            String actorRole,
            String action,
            String resourceType,
            Object resourceId,
            AuditLog.Result result,
            Map<String, ?> metadata) {
        save(actorId, actorRole, action, resourceType, resourceId, result, metadata);
    }

    private void save(
            UUID actorId,
            String actorRole,
            String action,
            String resourceType,
            Object resourceId,
            AuditLog.Result result,
            Map<String, ?> metadata) {
        repository.save(new AuditLog(
                UUID.randomUUID(),
                actorId,
                actorRole,
                action,
                resourceType,
                resourceId == null ? null : resourceId.toString(),
                result,
                CorrelationIdFilter.current(),
                ClientInfo.currentIp(),
                objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata),
                clock.instant()));
    }
}
