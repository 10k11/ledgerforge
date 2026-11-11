package dev.ledgerforge.transfer;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface TransferRepository extends JpaRepository<Transfer, UUID> {

    @Query("""
            select t from Transfer t
            where ((:direction = 'ALL' and (t.senderId = :userId or t.recipientId = :userId))
                or (:direction = 'SENT' and t.senderId = :userId)
                or (:direction = 'RECEIVED' and t.recipientId = :userId))
              and t.createdAt >= :from and t.createdAt < :to
              and (:q is null or lower(t.publicReference) like :q or lower(t.description) like :q)
            """)
    Page<Transfer> search(String direction, UUID userId, Instant from, Instant to, String q, Pageable pageable);
}
