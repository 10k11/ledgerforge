package dev.ledgerforge.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerforge.common.error.ApiException;
import dev.ledgerforge.common.error.ErrorCode;
import dev.ledgerforge.common.money.Currency;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LedgerValidationServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final LedgerValidationService validation = new LedgerValidationService();

    private final LedgerAccount walletA = account(AccountPurpose.CUSTOMER_WALLET, Currency.INR);
    private final LedgerAccount walletB = account(AccountPurpose.CUSTOMER_WALLET, Currency.INR);
    private final LedgerAccount payable = account(AccountPurpose.MERCHANT_PAYABLE, Currency.INR);
    private final LedgerAccount fees = account(AccountPurpose.FEE_REVENUE, Currency.INR);
    private final LedgerAccount usdWallet = account(AccountPurpose.CUSTOMER_WALLET, Currency.USD);

    private static LedgerAccount account(AccountPurpose purpose, Currency currency) {
        return new LedgerAccount(
                UUID.randomUUID(),
                "LF" + UUID.randomUUID().toString().substring(0, 8),
                purpose.isUserOwned() ? UUID.randomUUID() : null,
                purpose.label(),
                purpose,
                currency,
                !purpose.isUserOwned(),
                NOW);
    }

    private static String invariantOf(Throwable t) {
        return ((LedgerInvariantViolation) t).invariant();
    }

    @Test
    void acceptsBalancedPaymentWithFee() {
        LedgerPostingRequest req = LedgerPostingRequest.builder(LedgerTransactionType.PAYMENT, Currency.INR)
                .description("payment")
                .debit(walletA.getId(), 100_000)
                .credit(payable.getId(), 98_000)
                .credit(fees.getId(), 2_000)
                .build();
        assertThatCode(() -> validation.validateStructure(req)).doesNotThrowAnyException();
        assertThatCode(() -> validation.validateAccounts(
                        req, Map.of(walletA.getId(), walletA, payable.getId(), payable, fees.getId(), fees)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsSinglePosting() {
        LedgerPostingRequest req = LedgerPostingRequest.builder(LedgerTransactionType.TOP_UP, Currency.INR)
                .description("x")
                .credit(walletA.getId(), 100)
                .build();
        assertThatThrownBy(() -> validation.validateStructure(req))
                .satisfies(t -> assertThat(invariantOf(t)).isEqualTo("MIN_TWO_POSTINGS"));
    }

    @Test
    void rejectsUnbalancedPostings() {
        LedgerPostingRequest req = LedgerPostingRequest.builder(LedgerTransactionType.TRANSFER, Currency.INR)
                .description("x")
                .debit(walletA.getId(), 100)
                .credit(walletB.getId(), 99)
                .build();
        assertThatThrownBy(() -> validation.validateStructure(req))
                .satisfies(t -> assertThat(invariantOf(t)).isEqualTo("BALANCED"));
    }

    @Test
    void rejectsZeroAndNegativeAmounts() {
        for (long amount : new long[] {0, -100}) {
            LedgerPostingRequest req = LedgerPostingRequest.builder(LedgerTransactionType.TRANSFER, Currency.INR)
                    .description("x")
                    .debit(walletA.getId(), amount)
                    .credit(walletB.getId(), amount)
                    .build();
            assertThatThrownBy(() -> validation.validateStructure(req))
                    .satisfies(t -> assertThat(invariantOf(t)).isEqualTo("POSITIVE_AMOUNTS"));
        }
    }

    @Test
    void rejectsDebitAndCreditOfSameAccount() {
        LedgerPostingRequest req = LedgerPostingRequest.builder(LedgerTransactionType.TRANSFER, Currency.INR)
                .description("x")
                .debit(walletA.getId(), 100)
                .credit(walletA.getId(), 100)
                .build();
        assertThatThrownBy(() -> validation.validateStructure(req))
                .satisfies(t -> assertThat(invariantOf(t)).isEqualTo("SINGLE_DIRECTION_PER_ACCOUNT"));
    }

    @Test
    void reversalsMustReferenceOriginalAndOthersMustNot() {
        LedgerPostingRequest refundWithoutRef = LedgerPostingRequest.builder(LedgerTransactionType.REFUND, Currency.INR)
                .description("x")
                .debit(payable.getId(), 100)
                .credit(walletA.getId(), 100)
                .build();
        assertThatThrownBy(() -> validation.validateStructure(refundWithoutRef))
                .satisfies(t -> assertThat(invariantOf(t)).isEqualTo("REVERSAL_REFERENCE"));
        LedgerPostingRequest transferWithRef = LedgerPostingRequest.builder(
                        LedgerTransactionType.TRANSFER, Currency.INR)
                .description("x")
                .debit(walletA.getId(), 100)
                .credit(walletB.getId(), 100)
                .reverses(UUID.randomUUID())
                .build();
        assertThatThrownBy(() -> validation.validateStructure(transferWithRef))
                .satisfies(t -> assertThat(invariantOf(t)).isEqualTo("REVERSAL_REFERENCE"));
    }

    @Test
    void rejectsCurrencyMismatch() {
        LedgerPostingRequest req = LedgerPostingRequest.builder(LedgerTransactionType.TRANSFER, Currency.INR)
                .description("x")
                .debit(walletA.getId(), 100)
                .credit(usdWallet.getId(), 100)
                .build();
        assertThatThrownBy(() -> validation.validateAccounts(
                        req, Map.of(walletA.getId(), walletA, usdWallet.getId(), usdWallet)))
                .isInstanceOf(ApiException.class)
                .satisfies(t -> assertThat(((ApiException) t).code()).isEqualTo(ErrorCode.CURRENCY_MISMATCH));
    }

    @Test
    void rejectsUnknownAccount() {
        LedgerPostingRequest req = LedgerPostingRequest.builder(LedgerTransactionType.TRANSFER, Currency.INR)
                .description("x")
                .debit(walletA.getId(), 100)
                .credit(UUID.randomUUID(), 100)
                .build();
        assertThatThrownBy(() -> validation.validateAccounts(req, Map.of(walletA.getId(), walletA)))
                .satisfies(t -> assertThat(invariantOf(t)).isEqualTo("ACCOUNT_EXISTS"));
    }

    @Test
    void rejectsNegativeBalanceForNonOverdraftAccounts() {
        assertThatThrownBy(() -> validation.validateResultingBalance(walletA, -1))
                .satisfies(t -> assertThat(((ApiException) t).code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS));
        assertThatThrownBy(() -> validation.validateResultingBalance(payable, -1))
                .satisfies(
                        t -> assertThat(((ApiException) t).code()).isEqualTo(ErrorCode.MERCHANT_INSUFFICIENT_BALANCE));
        LedgerAccount clearing = account(AccountPurpose.FUNDING_CLEARING, Currency.INR);
        assertThatCode(() -> validation.validateResultingBalance(clearing, -500))
                .doesNotThrowAnyException();
        assertThatCode(() -> validation.validateResultingBalance(walletA, 0)).doesNotThrowAnyException();
    }

    // ------------------------------------------------------------------ reversals

    private LedgerTransaction original(LedgerTransactionType type) {
        return new LedgerTransaction(
                UUID.randomUUID(),
                "LTX_ORIG",
                type,
                "orig",
                Currency.INR,
                null,
                null,
                type.isReversal() ? UUID.randomUUID() : null,
                null,
                NOW);
    }

    private static LedgerEntry entry(LedgerTransaction tx, UUID account, EntryType type, long amount) {
        return new LedgerEntry(UUID.randomUUID(), tx, account, type, amount, Currency.INR, NOW);
    }

    @Test
    void reversalCannotExceedOriginalPerAccount() {
        LedgerTransaction payment = original(LedgerTransactionType.PAYMENT);
        List<LedgerEntry> originalEntries = List.of(
                entry(payment, walletA.getId(), EntryType.DEBIT, 100_000),
                entry(payment, payable.getId(), EntryType.CREDIT, 98_000),
                entry(payment, fees.getId(), EntryType.CREDIT, 2_000));
        LedgerTransaction priorRefund = original(LedgerTransactionType.PAYMENT);
        List<LedgerEntry> prior = List.of(
                entry(priorRefund, walletA.getId(), EntryType.CREDIT, 60_000),
                entry(priorRefund, payable.getId(), EntryType.DEBIT, 58_800),
                entry(priorRefund, fees.getId(), EntryType.DEBIT, 1_200));

        LedgerPostingRequest ok = refund(40_000, 39_200, 800, payment.getId());
        assertThatCode(() -> validation.validateReversal(ok, payment, originalEntries, prior))
                .doesNotThrowAnyException();

        LedgerPostingRequest tooMuch = refund(40_001, 39_201, 800, payment.getId());
        assertThatThrownBy(() -> validation.validateReversal(tooMuch, payment, originalEntries, prior))
                .satisfies(t -> assertThat(invariantOf(t)).isEqualTo("REVERSAL_NOT_EXCEEDED"));
    }

    @Test
    void reversalMustMirrorOriginalDirectionAndAccounts() {
        LedgerTransaction payment = original(LedgerTransactionType.PAYMENT);
        List<LedgerEntry> originalEntries = List.of(
                entry(payment, walletA.getId(), EntryType.DEBIT, 100),
                entry(payment, payable.getId(), EntryType.CREDIT, 100));
        // Same direction as the original: not a reversal.
        LedgerPostingRequest sameDirection = LedgerPostingRequest.builder(LedgerTransactionType.REFUND, Currency.INR)
                .description("x")
                .debit(walletA.getId(), 10)
                .credit(payable.getId(), 10)
                .reverses(payment.getId())
                .build();
        assertThatThrownBy(() -> validation.validateReversal(sameDirection, payment, originalEntries, List.of()))
                .satisfies(t -> assertThat(invariantOf(t)).isEqualTo("REVERSAL_MIRRORS_ORIGINAL"));
        // Account not part of the original.
        LedgerPostingRequest foreign = LedgerPostingRequest.builder(LedgerTransactionType.REFUND, Currency.INR)
                .description("x")
                .debit(payable.getId(), 10)
                .credit(walletB.getId(), 10)
                .reverses(payment.getId())
                .build();
        assertThatThrownBy(() -> validation.validateReversal(foreign, payment, originalEntries, List.of()))
                .satisfies(t -> assertThat(invariantOf(t)).isEqualTo("REVERSAL_MIRRORS_ORIGINAL"));
    }

    @Test
    void cannotReverseAReversal() {
        LedgerTransaction refund = original(LedgerTransactionType.REFUND);
        LedgerPostingRequest req = refund(10, 10, 0, refund.getId());
        assertThatThrownBy(() -> validation.validateReversal(req, refund, List.of(), List.of()))
                .satisfies(t -> assertThat(invariantOf(t)).isEqualTo("REVERSAL_REFERENCE"));
    }

    private LedgerPostingRequest refund(long amount, long merchantDebit, long feeReversal, UUID originalId) {
        LedgerPostingRequest.Builder b = LedgerPostingRequest.builder(LedgerTransactionType.REFUND, Currency.INR)
                .description("refund")
                .credit(walletA.getId(), amount)
                .debit(payable.getId(), merchantDebit)
                .reverses(originalId);
        if (feeReversal > 0) {
            b.debit(fees.getId(), feeReversal);
        }
        return b.build();
    }

    @Test
    void accountTypeSignedDeltaFollowsNormalBalance() throws Exception {
        assertThat(AccountType.LIABILITY.signedDelta(EntryType.CREDIT, 100)).isEqualTo(100);
        assertThat(AccountType.LIABILITY.signedDelta(EntryType.DEBIT, 100)).isEqualTo(-100);
        assertThat(AccountType.ASSET.signedDelta(EntryType.DEBIT, 100)).isEqualTo(100);
        assertThat(AccountType.REVENUE.signedDelta(EntryType.DEBIT, 100)).isEqualTo(-100);
        assertThat(AccountType.EXPENSE.signedDelta(EntryType.DEBIT, 100)).isEqualTo(100);
        Field f = LedgerAccount.class.getDeclaredField("normalBalance");
        f.setAccessible(true);
        assertThat(f.get(walletA)).isEqualTo(EntryType.CREDIT);
    }
}
