package dev.ledgerforge.ledger;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface AccountBalanceRepository extends JpaRepository<AccountBalance, UUID> {

    Optional<AccountBalance> findByLedgerAccountId(UUID ledgerAccountId);

    List<AccountBalance> findByLedgerAccountIdIn(List<UUID> ledgerAccountIds);

    /**
     * {@code SELECT ... FOR UPDATE} on a single balance row. Callers lock multiple accounts one at a
     * time in a deterministic order (see {@link LedgerService#lockBalances}) to avoid deadlocks. A 5s
     * lock timeout turns a pathological wait into a retryable 409 instead of a hung request.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select b from AccountBalance b where b.ledgerAccountId = :ledgerAccountId")
    Optional<AccountBalance> lockByLedgerAccountId(UUID ledgerAccountId);
}
