package dev.ledgerforge.payment;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface PaymentIntentRepository
        extends JpaRepository<PaymentIntent, UUID>, JpaSpecificationExecutor<PaymentIntent> {

    /**
     * Row lock on the intent. Confirmations, cancellations and refunds of the same payment are
     * serialised here, before any balance row is locked (consistent lock order: intent, then
     * balances in account-id order).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select p from PaymentIntent p where p.id = :id")
    Optional<PaymentIntent> lockById(UUID id);
}
