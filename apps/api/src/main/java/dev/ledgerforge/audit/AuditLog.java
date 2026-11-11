package dev.ledgerforge.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "audit_logs")
public class AuditLog {

    public enum Result {
        SUCCESS,
        FAILURE,
        DENIED
    }

    @Id
    private UUID id;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_role", length = 16)
    private String actorRole;

    @Column(nullable = false, length = 64)
    private String action;

    @Column(name = "resource_type", nullable = false, length = 32)
    private String resourceType;

    @Column(name = "resource_id", length = 64)
    private String resourceId;

    @Column(nullable = false, length = 16)
    private String result;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String metadata;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditLog() {}

    AuditLog(
            UUID id,
            UUID actorId,
            String actorRole,
            String action,
            String resourceType,
            String resourceId,
            Result result,
            String correlationId,
            String ipAddress,
            String metadata,
            Instant createdAt) {
        this.id = id;
        this.actorId = actorId;
        this.actorRole = actorRole;
        this.action = action;
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.result = result.name();
        this.correlationId = correlationId;
        this.ipAddress = ipAddress;
        this.metadata = metadata;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getActorRole() {
        return actorRole;
    }

    public String getAction() {
        return action;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }

    public String getResult() {
        return result;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getMetadata() {
        return metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
