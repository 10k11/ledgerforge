package dev.ledgerforge.seed;

import dev.ledgerforge.auth.PasswordPolicy;
import dev.ledgerforge.common.config.AppProperties;
import dev.ledgerforge.common.money.Currency;
import dev.ledgerforge.user.AccountProvisioningService;
import dev.ledgerforge.user.Role;
import dev.ledgerforge.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates the first administrator from {@code BOOTSTRAP_ADMIN_EMAIL}/{@code
 * BOOTSTRAP_ADMIN_PASSWORD} if both are set and the account does not exist. Administrators can
 * never self-register. Safe to run in any environment; it does nothing when unset.
 */
@Component
@Order(1)
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AppProperties props;
    private final UserRepository users;
    private final AccountProvisioningService provisioning;
    private final TransactionTemplate tx;

    public AdminBootstrap(
            AppProperties props,
            UserRepository users,
            AccountProvisioningService provisioning,
            PlatformTransactionManager txManager) {
        this.props = props;
        this.users = users;
        this.provisioning = provisioning;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        String email = props.bootstrapAdmin().email();
        String password = props.bootstrapAdmin().password();
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            return;
        }
        String normalised = AccountProvisioningService.normaliseEmail(email);
        if (users.existsByEmail(normalised)) {
            return;
        }
        PasswordPolicy.validate(password, normalised, "BOOTSTRAP_ADMIN_PASSWORD");
        tx.executeWithoutResult(s -> provisioning.provision(new AccountProvisioningService.NewUser(
                normalised, "Platform Administrator", password, Role.ADMIN, Currency.INR, null, null)));
        log.info("Bootstrap administrator account created");
    }
}
