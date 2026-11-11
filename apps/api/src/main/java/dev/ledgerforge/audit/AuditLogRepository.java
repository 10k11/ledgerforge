package dev.ledgerforge.audit;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    @Query("""
            select a from AuditLog a
            where (:action is null or a.action = :action)
              and (:actorId is null or a.actorId = :actorId)
            order by a.createdAt desc
            """)
    Page<AuditLog> search(String action, UUID actorId, Pageable pageable);
}
