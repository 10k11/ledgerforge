package dev.ledgerforge.ledger;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

    Optional<LedgerTransaction> findByReferenceNumber(String referenceNumber);

    List<LedgerTransaction> findByReversesTransactionIdOrderByCreatedAt(UUID reversesTransactionId);

    /**
     * Serialises concurrent reversals of the same original transaction. A native row lock is used
     * because Hibernate rejects lock modes on {@code @Immutable} entities; FOR UPDATE does not fire
     * the immutability trigger (no row is modified).
     */
    @Query(value = "SELECT * FROM ledger_transactions WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<LedgerTransaction> lockById(UUID id);
}
