package dev.ledgerforge.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerforge.common.error.ApiException;
import dev.ledgerforge.common.error.ErrorCode;
import dev.ledgerforge.common.money.Currency;
import dev.ledgerforge.ledger.AccountPurpose;
import dev.ledgerforge.ledger.LedgerAccount;
import dev.ledgerforge.ledger.LedgerAccountService;
import dev.ledgerforge.ledger.LedgerInvariantViolation;
import dev.ledgerforge.ledger.LedgerPostingRequest;
import dev.ledgerforge.ledger.LedgerService;
import dev.ledgerforge.ledger.LedgerTransactionType;
import dev.ledgerforge.ledger.ReconciliationService;
import dev.ledgerforge.support.ApiClient;
import dev.ledgerforge.support.IntegrationTestBase;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionTemplate;

/** Exercises the ledger engine directly and the database-level invariants behind it. */
class LedgerEngineIT extends IntegrationTestBase {

    @Autowired
    LedgerService ledger;

    @Autowired
    LedgerAccountService accounts;

    @Autowired
    ReconciliationService reconciliation;

    private LedgerAccount wallet(ApiClient c) {
        return accounts.findUserAccount(c.userId(), AccountPurpose.CUSTOMER_WALLET, Currency.INR)
                .orElseThrow();
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    @Test
    void postsBalancedTransactionAndUpdatesProjectionAtomically() {
        ApiClient a = customer("ledger-a");
        ApiClient b = customer("ledger-b");
        LedgerAccount wa = wallet(a);
        LedgerAccount wb = wallet(b);
        LedgerAccount funding = accounts.systemAccount(AccountPurpose.FUNDING_CLEARING, Currency.INR);
        long fundingBefore = ledger.balanceOf(funding.getId());

        tx().executeWithoutResult(
                        s -> ledger.post(LedgerPostingRequest.builder(LedgerTransactionType.TOP_UP, Currency.INR)
                                .description("test top-up")
                                .debit(funding.getId(), 50_000)
                                .credit(wa.getId(), 50_000)
                                .build()));
        tx().executeWithoutResult(
                        s -> ledger.post(LedgerPostingRequest.builder(LedgerTransactionType.TRANSFER, Currency.INR)
                                .description("test transfer")
                                .debit(wa.getId(), 20_000)
                                .credit(wb.getId(), 20_000)
                                .build()));

        assertThat(ledger.balanceOf(wa.getId())).isEqualTo(30_000);
        assertThat(ledger.balanceOf(wb.getId())).isEqualTo(20_000);
        assertThat(ledger.balanceOf(funding.getId())).isEqualTo(fundingBefore + 50_000);
        assertThat(reconciliation.reconcile().healthy()).isTrue();
    }

    @Test
    void ledgerRequiresAnEnclosingTransaction() {
        assertThatThrownBy(() -> ledger.post(LedgerPostingRequest.builder(LedgerTransactionType.TOP_UP, Currency.INR)
                        .description("x")
                        .debit(UUID.randomUUID(), 1)
                        .credit(UUID.randomUUID(), 1)
                        .build()))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void insufficientFundsRollsBackEverything() {
        ApiClient a = customer("nsf-a");
        ApiClient b = customer("nsf-b");
        LedgerAccount wa = wallet(a);
        LedgerAccount wb = wallet(b);
        long txBefore = dbCount("SELECT COUNT(*) FROM ledger_transactions");
        assertThatThrownBy(() -> tx().executeWithoutResult(s ->
                        ledger.post(LedgerPostingRequest.builder(LedgerTransactionType.TRANSFER, Currency.INR)
                                .description("overdraft")
                                .debit(wa.getId(), 1)
                                .credit(wb.getId(), 1)
                                .build())))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS));
        assertThat(dbCount("SELECT COUNT(*) FROM ledger_transactions")).isEqualTo(txBefore);
        assertThat(ledger.balanceOf(wa.getId())).isZero();
        assertThat(ledger.balanceOf(wb.getId())).isZero();
    }

    @Test
    void failureAfterPostingInSameTransactionLeavesNoPartialPostings() {
        ApiClient a = customer("rollback");
        LedgerAccount wa = wallet(a);
        LedgerAccount funding = accounts.systemAccount(AccountPurpose.FUNDING_CLEARING, Currency.INR);
        long entriesBefore = dbCount("SELECT COUNT(*) FROM ledger_entries");
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> {
                    ledger.post(LedgerPostingRequest.builder(LedgerTransactionType.TOP_UP, Currency.INR)
                            .description("will roll back")
                            .debit(funding.getId(), 10_000)
                            .credit(wa.getId(), 10_000)
                            .build());
                    throw new IllegalStateException("simulated failure after posting (e.g. outbox write failed)");
                }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(dbCount("SELECT COUNT(*) FROM ledger_entries")).isEqualTo(entriesBefore);
        assertThat(ledger.balanceOf(wa.getId())).isZero();
    }

    @Test
    void currencyMismatchIsRejected() {
        ApiClient a = customer("fx");
        LedgerAccount wa = wallet(a);
        LedgerAccount usdFunding = accounts.systemAccount(AccountPurpose.FUNDING_CLEARING, Currency.USD);
        assertThatThrownBy(() -> tx().executeWithoutResult(s ->
                        ledger.post(LedgerPostingRequest.builder(LedgerTransactionType.TOP_UP, Currency.INR)
                                .description("fx")
                                .debit(usdFunding.getId(), 100)
                                .credit(wa.getId(), 100)
                                .build())))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCode.CURRENCY_MISMATCH));
    }

    @Test
    void reversalCannotExceedOriginal() {
        ApiClient a = customer("rev");
        LedgerAccount wa = wallet(a);
        LedgerAccount funding = accounts.systemAccount(AccountPurpose.FUNDING_CLEARING, Currency.INR);
        UUID original = tx().execute(
                        s -> ledger.post(LedgerPostingRequest.builder(LedgerTransactionType.TOP_UP, Currency.INR)
                                        .description("orig")
                                        .debit(funding.getId(), 1_000)
                                        .credit(wa.getId(), 1_000)
                                        .build())
                                .id());
        tx().executeWithoutResult(
                        s -> ledger.post(LedgerPostingRequest.builder(LedgerTransactionType.REVERSAL, Currency.INR)
                                .description("partial reversal")
                                .debit(wa.getId(), 600)
                                .credit(funding.getId(), 600)
                                .reverses(original)
                                .build()));
        assertThatThrownBy(() -> tx().executeWithoutResult(s ->
                        ledger.post(LedgerPostingRequest.builder(LedgerTransactionType.REVERSAL, Currency.INR)
                                .description("too much")
                                .debit(wa.getId(), 401)
                                .credit(funding.getId(), 401)
                                .reverses(original)
                                .build())))
                .isInstanceOf(LedgerInvariantViolation.class)
                .satisfies(e ->
                        assertThat(((LedgerInvariantViolation) e).invariant()).isEqualTo("REVERSAL_NOT_EXCEEDED"));
        assertThat(ledger.balanceOf(wa.getId())).isEqualTo(400);
    }

    // ------------------------------------------------------------------ database-level defences

    @Test
    void databaseRejectsUpdatesAndDeletesOfPostedEntries() {
        ApiClient a = customer("immutable");
        topUp(a, 5_000);
        UUID entryId = jdbc.queryForObject(
                "SELECT e.id FROM ledger_entries e JOIN ledger_accounts a ON a.id = e.ledger_account_id"
                        + " WHERE a.owner_id = ? LIMIT 1",
                UUID.class,
                a.userId());
        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET amount_minor = 1 WHERE id = ?", entryId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("LEDGER_IMMUTABLE");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries WHERE id = ?", entryId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("LEDGER_IMMUTABLE");
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE ledger_transactions SET description = 'tampered' WHERE id ="
                                + " (SELECT ledger_transaction_id FROM ledger_entries WHERE id = ?)",
                        entryId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("LEDGER_IMMUTABLE");
    }

    @Test
    void databaseRejectsUnbalancedTransactionAtCommitEvenIfApplicationIsBypassed() {
        ApiClient a = customer("rawsql");
        LedgerAccount wa = wallet(a);
        LedgerAccount funding = accounts.systemAccount(AccountPurpose.FUNDING_CLEARING, Currency.INR);
        UUID txId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> {
                    jdbc.update(
                            "INSERT INTO ledger_transactions (id, reference_number, transaction_type, status, description,"
                                    + " currency, created_at, posted_at) VALUES (?, ?, 'TOP_UP', 'POSTED', 'raw', 'INR', ?, ?)",
                            txId,
                            "LTX_RAW" + txId.toString().substring(0, 8),
                            now,
                            now);
                    jdbc.update(
                            "INSERT INTO ledger_entries (id, ledger_transaction_id, ledger_account_id, entry_type,"
                                    + " amount_minor, currency, created_at) VALUES (?, ?, ?, 'DEBIT', 100, 'INR', ?)",
                            UUID.randomUUID(),
                            txId,
                            funding.getId(),
                            now);
                    jdbc.update(
                            "INSERT INTO ledger_entries (id, ledger_transaction_id, ledger_account_id, entry_type,"
                                    + " amount_minor, currency, created_at) VALUES (?, ?, ?, 'CREDIT', 99, 'INR', ?)",
                            UUID.randomUUID(),
                            txId,
                            wa.getId(),
                            now);
                }))
                .hasMessageContaining("LEDGER_UNBALANCED");
        assertThat(dbCount("SELECT COUNT(*) FROM ledger_transactions WHERE id = ?", txId))
                .isZero();
    }

    @Test
    void databaseRejectsEntryCurrencyMismatchAndNonPositiveAmounts() {
        ApiClient a = customer("rawcur");
        LedgerAccount wa = wallet(a);
        UUID txId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        assertThatThrownBy(() -> tx().executeWithoutResult(s -> {
                    jdbc.update(
                            "INSERT INTO ledger_transactions (id, reference_number, transaction_type, status, description,"
                                    + " currency, created_at, posted_at) VALUES (?, ?, 'TOP_UP', 'POSTED', 'raw', 'USD', ?, ?)",
                            txId,
                            "LTX_CUR" + txId.toString().substring(0, 8),
                            now,
                            now);
                    jdbc.update(
                            "INSERT INTO ledger_entries (id, ledger_transaction_id, ledger_account_id, entry_type,"
                                    + " amount_minor, currency, created_at) VALUES (?, ?, ?, 'CREDIT', 100, 'USD', ?)",
                            UUID.randomUUID(),
                            txId,
                            wa.getId(),
                            now);
                }))
                .hasMessageContaining("LEDGER_CURRENCY_MISMATCH");
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO ledger_entries (id, ledger_transaction_id, ledger_account_id, entry_type,"
                                + " amount_minor, currency, created_at) VALUES (?, ?, ?, 'CREDIT', 0, 'INR', ?)",
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        wa.getId(),
                        now))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void databaseRejectsNegativeWalletBalanceProjection() {
        ApiClient a = customer("negbal");
        LedgerAccount wa = wallet(a);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE account_balances SET balance_minor = -1 WHERE ledger_account_id = ?", wa.getId()))
                .hasMessageContaining("ck_account_balances_non_negative");
    }

    @Test
    void reconciliationDetectsTamperedProjection() {
        ApiClient a = customer("tamper");
        topUp(a, 1_000);
        LedgerAccount wa = wallet(a);
        assertThat(reconciliation.reconcile().balanceMismatches()).isEmpty();
        try {
            jdbc.update(
                    "UPDATE account_balances SET balance_minor = balance_minor + 1 WHERE ledger_account_id = ?",
                    wa.getId());
            ReconciliationService.Report report = reconciliation.reconcile();
            assertThat(report.healthy()).isFalse();
            assertThat(report.balanceMismatches())
                    .anySatisfy(m -> assertThat(m.accountNumber()).isEqualTo(wa.getAccountNumber()));
        } finally {
            jdbc.update(
                    "UPDATE account_balances SET balance_minor = balance_minor - 1 WHERE ledger_account_id = ?",
                    wa.getId());
        }
        assertThat(reconciliation.reconcile().healthy()).isTrue();
    }
}
