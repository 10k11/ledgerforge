package dev.ledgerforge.ledger;

import dev.ledgerforge.common.error.ApiException;
import dev.ledgerforge.common.money.Currency;
import dev.ledgerforge.common.util.ReferenceGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Opens and looks up ledger accounts. Every opened account gets a zero balance row. */
@Service
public class LedgerAccountService {

    private final LedgerAccountRepository accounts;
    private final AccountBalanceRepository balances;
    private final Clock clock;

    public LedgerAccountService(LedgerAccountRepository accounts, AccountBalanceRepository balances, Clock clock) {
        this.accounts = accounts;
        this.balances = balances;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerAccount openCustomerWallet(UUID ownerId, Currency currency, String ownerName) {
        return open(ownerId, AccountPurpose.CUSTOMER_WALLET, currency, "Wallet · " + ownerName);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerAccount openMerchantPayable(UUID ownerId, Currency currency, String businessName) {
        return open(ownerId, AccountPurpose.MERCHANT_PAYABLE, currency, "Merchant Payable · " + businessName);
    }

    private LedgerAccount open(UUID ownerId, AccountPurpose purpose, Currency currency, String name) {
        Instant now = clock.instant();
        LedgerAccount account = new LedgerAccount(
                UUID.randomUUID(), uniqueAccountNumber(), ownerId, truncate(name), purpose, currency, false, now);
        accounts.save(account);
        balances.save(new AccountBalance(UUID.randomUUID(), account.getId(), account.isAllowNegative(), now));
        return account;
    }

    @Transactional(readOnly = true)
    public Optional<LedgerAccount> findUserAccount(UUID ownerId, AccountPurpose purpose, Currency currency) {
        return accounts.findByOwnerIdAndPurposeAndCurrency(ownerId, purpose, currency);
    }

    @Transactional(readOnly = true)
    public List<LedgerAccount> accountsOwnedBy(UUID ownerId) {
        return accounts.findByOwnerId(ownerId);
    }

    @Transactional(readOnly = true)
    public LedgerAccount systemAccount(AccountPurpose purpose, Currency currency) {
        return accounts.findSystemAccount(purpose, currency)
                .orElseThrow(
                        () -> new IllegalStateException("System account " + purpose + "/" + currency + " missing"));
    }

    @Transactional(readOnly = true)
    public Optional<LedgerAccount> findByAccountNumber(String accountNumber) {
        return accounts.findByAccountNumber(accountNumber);
    }

    @Transactional(readOnly = true)
    public LedgerAccount get(UUID id) {
        return accounts.findById(id).orElseThrow(() -> ApiException.notFound("Ledger account"));
    }

    private String uniqueAccountNumber() {
        for (int i = 0; i < 5; i++) {
            String candidate = "LF" + ReferenceGenerator.randomDigits(12);
            if (!accounts.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not allocate a unique account number");
    }

    private static String truncate(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120);
    }
}
