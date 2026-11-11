package dev.ledgerforge.ledger;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    @Query("select e from LedgerEntry e where e.transaction.id = :transactionId order by e.sequenceNumber")
    List<LedgerEntry> findByTransactionId(UUID transactionId);

    /** All entries of all reversals that reference the given original transaction. */
    @Query("""
            select e from LedgerEntry e
            where e.transaction.reversesTransactionId = :originalId
            """)
    List<LedgerEntry> findReversalEntriesOf(UUID originalId);

    @Query("""
            select coalesce(sum(case when e.entryType = dev.ledgerforge.ledger.EntryType.DEBIT
                                     then e.amountMinor else 0 end), 0)
            from LedgerEntry e where e.ledgerAccountId = :accountId
            """)
    long sumDebits(UUID accountId);

    @Query("""
            select coalesce(sum(case when e.entryType = dev.ledgerforge.ledger.EntryType.CREDIT
                                     then e.amountMinor else 0 end), 0)
            from LedgerEntry e where e.ledgerAccountId = :accountId
            """)
    long sumCredits(UUID accountId);
}
