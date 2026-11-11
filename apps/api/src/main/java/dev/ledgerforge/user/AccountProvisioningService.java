package dev.ledgerforge.user;

import dev.ledgerforge.common.error.ApiException;
import dev.ledgerforge.common.error.ErrorCode;
import dev.ledgerforge.common.money.Currency;
import dev.ledgerforge.ledger.LedgerAccountService;
import dev.ledgerforge.merchant.MerchantProfile;
import dev.ledgerforge.merchant.MerchantProfileRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a user together with the ledger accounts their role needs, atomically: a customer gets
 * a wallet (liability) account; a merchant gets a profile and a payable (liability) account.
 */
@Service
public class AccountProvisioningService {

    private final UserRepository users;
    private final MerchantProfileRepository merchants;
    private final LedgerAccountService ledgerAccounts;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    public AccountProvisioningService(
            UserRepository users,
            MerchantProfileRepository merchants,
            LedgerAccountService ledgerAccounts,
            PasswordEncoder passwordEncoder,
            Clock clock) {
        this.users = users;
        this.merchants = merchants;
        this.ledgerAccounts = ledgerAccounts;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
    }

    public record NewUser(
            String email,
            String fullName,
            String rawPassword,
            Role role,
            Currency currency,
            String businessName,
            String category) {}

    @Transactional(propagation = Propagation.MANDATORY)
    public User provision(NewUser request) {
        String email = normaliseEmail(request.email());
        if (users.existsByEmail(email)) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED, "An account with this email already exists");
        }
        Instant now = clock.instant();
        User user = new User(
                UUID.randomUUID(),
                email,
                request.fullName().trim(),
                passwordEncoder.encode(request.rawPassword()),
                request.role(),
                request.currency(),
                now);
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent registration for the same email.
            throw new ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED, "An account with this email already exists");
        }
        switch (request.role()) {
            case CUSTOMER -> ledgerAccounts.openCustomerWallet(user.getId(), request.currency(), user.getFullName());
            case MERCHANT -> {
                String business = request.businessName().trim();
                merchants.save(new MerchantProfile(
                        UUID.randomUUID(),
                        user.getId(),
                        business,
                        request.category() == null || request.category().isBlank()
                                ? "General"
                                : request.category().trim(),
                        email,
                        now));
                ledgerAccounts.openMerchantPayable(user.getId(), request.currency(), business);
            }
            case ADMIN -> {
                // Administrators operate the platform; they hold no customer funds.
            }
        }
        return user;
    }

    public static String normaliseEmail(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
