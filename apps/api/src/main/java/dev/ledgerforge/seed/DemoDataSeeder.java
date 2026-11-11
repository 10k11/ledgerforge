package dev.ledgerforge.seed;

import dev.ledgerforge.common.config.AppProperties;
import dev.ledgerforge.common.money.Currency;
import dev.ledgerforge.common.security.AuthenticatedUser;
import dev.ledgerforge.payment.PaymentDtos;
import dev.ledgerforge.payment.PaymentService;
import dev.ledgerforge.refund.RefundService;
import dev.ledgerforge.transfer.TransferDtos;
import dev.ledgerforge.transfer.TransferService;
import dev.ledgerforge.user.AccountProvisioningService;
import dev.ledgerforge.user.Role;
import dev.ledgerforge.user.User;
import dev.ledgerforge.user.UserRepository;
import dev.ledgerforge.wallet.WalletDtos;
import dev.ledgerforge.wallet.WalletService;
import dev.ledgerforge.webhook.EventTypes;
import dev.ledgerforge.webhook.WebhookDtos;
import dev.ledgerforge.webhook.WebhookService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Development-only demo data. Runs only when {@code app.seed.enabled=true} (default only in the
 * {@code dev} profile) and never in production ({@link
 * dev.ledgerforge.common.config.ProductionSafetyGuard} refuses to start otherwise).
 *
 * <p>All demo activity goes through the real services (ledger postings, idempotency, outbox), so
 * the seeded data is indistinguishable from data created through the API.
 */
@Component
@Order(2)
public class DemoDataSeeder implements ApplicationRunner {

    public static final String DEMO_PASSWORD = "Sandbox-Demo-2026";
    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final AppProperties props;
    private final UserRepository users;
    private final AccountProvisioningService provisioning;
    private final WalletService wallets;
    private final TransferService transfers;
    private final PaymentService payments;
    private final RefundService refunds;
    private final WebhookService webhooks;
    private final TransactionTemplate tx;

    public DemoDataSeeder(
            AppProperties props,
            UserRepository users,
            AccountProvisioningService provisioning,
            WalletService wallets,
            TransferService transfers,
            PaymentService payments,
            RefundService refunds,
            WebhookService webhooks,
            PlatformTransactionManager txManager) {
        this.props = props;
        this.users = users;
        this.provisioning = provisioning;
        this.wallets = wallets;
        this.transfers = transfers;
        this.payments = payments;
        this.refunds = refunds;
        this.webhooks = webhooks;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.seed().enabled() || props.isProduction()) {
            return;
        }
        if (users.existsByEmail("asha@ledgerforge.test")) {
            log.info("Demo data already present; skipping seed");
            return;
        }
        AuthenticatedUser admin = create("admin@ledgerforge.test", "Ops Admin", Role.ADMIN, null);
        AuthenticatedUser asha = create("asha@ledgerforge.test", "Asha Verma", Role.CUSTOMER, null);
        AuthenticatedUser ravi = create("ravi@ledgerforge.test", "Ravi Kumar", Role.CUSTOMER, null);
        AuthenticatedUser chai = create("merchant@ledgerforge.test", "Meera Iyer", Role.MERCHANT, "Chai & Co");
        AuthenticatedUser books = create("books@ledgerforge.test", "Arjun Rao", Role.MERCHANT, "Paper Lantern Books");

        wallets.topUp(asha, "seed-topup-asha-1", new WalletDtos.TopUpRequest(5_000_000, "INR"));
        wallets.topUp(ravi, "seed-topup-ravi-1", new WalletDtos.TopUpRequest(2_000_000, "INR"));
        transfers.create(
                asha,
                "seed-transfer-1",
                new TransferDtos.CreateTransferRequest("ravi@ledgerforge.test", 250_000, "INR", "Dinner split"));
        transfers.create(
                ravi,
                "seed-transfer-2",
                new TransferDtos.CreateTransferRequest("asha@ledgerforge.test", 75_000, "INR", "Movie tickets"));

        webhooks.create(
                chai,
                new WebhookDtos.CreateWebhookRequest(
                        props.selfBaseUrl() + "/api/v1/sandbox/webhook-sink/" + chai.id(),
                        "Built-in sandbox receiver",
                        List.of(EventTypes.PAYMENT_SUCCEEDED, EventTypes.PAYMENT_REFUNDED, EventTypes.PAYMENT_FAILED)));

        UUID p1 = pay(asha, chai, 100_000, "Masala chai subscription", "seed-pay-1");
        pay(asha, books, 189_900, "Three paperbacks", "seed-pay-2");
        UUID p3 = pay(ravi, chai, 45_000, "Office snacks", "seed-pay-3");
        pay(ravi, books, 25_013, "Gift card (declined by sandbox rule)", "seed-pay-4");
        refunds.create(
                chai,
                p1,
                "seed-refund-1",
                new RefundService.CreateRefundRequest(30_000L, "Partial refund: one box missing"));
        refunds.create(chai, p3, "seed-refund-2", new RefundService.CreateRefundRequest(null, "Order cancelled"));
        payments.create(
                asha,
                "seed-pay-5-create",
                new PaymentDtos.CreatePaymentIntentRequest(
                        chai.id(), 32_000, "INR", "Awaiting confirmation", Map.of("orderId", "CC-1088")));

        log.info(
                "Demo data seeded. Accounts use password '{}' (development only). Admin: {}",
                DEMO_PASSWORD,
                admin.email());
    }

    private AuthenticatedUser create(String email, String name, Role role, String business) {
        User user = tx.execute(s -> provisioning.provision(new AccountProvisioningService.NewUser(
                email, name, DEMO_PASSWORD, role, Currency.INR, business, business == null ? null : "Retail")));
        return new AuthenticatedUser(user.getId(), user.getEmail(), role, UUID.randomUUID());
    }

    private UUID pay(
            AuthenticatedUser customer, AuthenticatedUser merchant, long amount, String description, String key) {
        PaymentDtos.PaymentIntentView intent = payments.create(
                        customer,
                        key + "-create",
                        new PaymentDtos.CreatePaymentIntentRequest(merchant.id(), amount, "INR", description, Map.of()))
                .body();
        payments.confirm(customer, intent.id(), key + "-confirm");
        return intent.id();
    }
}
