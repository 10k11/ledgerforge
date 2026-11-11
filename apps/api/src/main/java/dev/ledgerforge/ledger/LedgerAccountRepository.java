package dev.ledgerforge.ledger;

import dev.ledgerforge.common.money.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {

    Optional<LedgerAccount> findByOwnerIdAndPurposeAndCurrency(UUID ownerId, AccountPurpose purpose, Currency currency);

    List<LedgerAccount> findByOwnerId(UUID ownerId);

    Optional<LedgerAccount> findByAccountNumber(String accountNumber);

    boolean existsByAccountNumber(String accountNumber);

    @Query("select a from LedgerAccount a where a.ownerId is null and a.purpose = :purpose and a.currency = :currency")
    Optional<LedgerAccount> findSystemAccount(AccountPurpose purpose, Currency currency);

    @Query("select a from LedgerAccount a where a.ownerId is null order by a.currency, a.accountNumber")
    List<LedgerAccount> findSystemAccounts();
}
