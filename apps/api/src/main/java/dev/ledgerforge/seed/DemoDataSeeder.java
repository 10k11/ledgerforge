package dev.ledgerforge.seed;

import dev.ledgerforge.common.config.AppProperties;
import dev.ledgerforge.common.config.ShiftableClock;
import dev.ledgerforge.common.error.ApiException;
import dev.ledgerforge.common.money.Currency;
import dev.ledgerforge.common.security.AuthenticatedUser;
import dev.ledgerforge.merchant.MerchantDtos;
import dev.ledgerforge.merchant.MerchantService;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;
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
 * <p>It replays ~30 days of deterministic activity through the real services (ledger postings,
 * idempotency, outbox, audit), using {@link ShiftableClock#runAt} for realistic timestamps, so the
 * seeded data is indistinguishable from data created through the API.
 */
@Component
@Order(2)
public class DemoDataSeeder implements ApplicationRunner {

    public static final String DEMO_PASSWORD = "Sandbox-Demo-2026";
    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final int DAYS = 30;

    private final AppProperties props;
    private final UserRepository users;
    private final AccountProvisioningService provisioning;
    private final WalletService wallets;
    private final TransferService transfers;
    private final PaymentService payments;
    private final RefundService refunds;
    private final MerchantService merchants;
    private final WebhookService webhooks;
    private final ShiftableClock clock;
    private final TransactionTemplate tx;

    public DemoDataSeeder(
            AppProperties props,
            UserRepository users,
            AccountProvisioningService provisioning,
            WalletService wallets,
            TransferService transfers,
            PaymentService payments,
            RefundService refunds,
            MerchantService merchants,
            WebhookService webhooks,
            ShiftableClock clock,
            PlatformTransactionManager txManager) {
        this.props = props;
        this.users = users;
        this.provisioning = provisioning;
        this.wallets = wallets;
        this.transfers = transfers;
        this.payments = payments;
        this.refunds = refunds;
        this.merchants = merchants;
        this.webhooks = webhooks;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    private record Purchase(AuthenticatedUser merchant, String description, long minAmount, long maxAmount) {}

    @Override
    public void run(ApplicationArguments args) {
        if (!props.seed().enabled() || props.isProduction()) {
            return;
        }
        if (users.existsByEmail("asha@ledgerforge.test")) {
            log.info("Demo data already present; skipping seed");
            return;
        }
        Random random = new Random(20261111L);
        LocalDate today = LocalDate.now(clock);
        Instant start = today.minusDays(DAYS - 1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .plus(Duration.ofHours(4));

        // ---- people (created on day one) ------------------------------------------------
        AuthenticatedUser admin =
                at(start, () -> create("admin@ledgerforge.test", "Ops Admin", Role.ADMIN, null, null));
        AuthenticatedUser asha =
                at(start, () -> create("asha@ledgerforge.test", "Asha Verma", Role.CUSTOMER, null, null));
        AuthenticatedUser ravi =
                at(start, () -> create("ravi@ledgerforge.test", "Ravi Kumar", Role.CUSTOMER, null, null));
        AuthenticatedUser neha =
                at(start, () -> create("neha@ledgerforge.test", "Neha Sharma", Role.CUSTOMER, null, null));
        AuthenticatedUser kabir =
                at(start, () -> create("kabir@ledgerforge.test", "Kabir Singh", Role.CUSTOMER, null, null));
        AuthenticatedUser chai = at(
                start,
                () -> create("merchant@ledgerforge.test", "Meera Iyer", Role.MERCHANT, "Chai & Co", "Food & Beverage"));
        AuthenticatedUser books = at(
                start,
                () -> create("books@ledgerforge.test", "Arjun Rao", Role.MERCHANT, "Paper Lantern Books", "Retail"));
        AuthenticatedUser pixel = at(
                start,
                () -> create(
                        "pixel@ledgerforge.test", "Tara Nair", Role.MERCHANT, "Pixel Forge Studio", "Digital goods"));
        List<AuthenticatedUser> customers = List.of(asha, ravi, neha, kabir);

        at(
                start,
                () -> webhooks.create(
                        chai,
                        new WebhookDtos.CreateWebhookRequest(
                                props.selfBaseUrl() + "/api/v1/sandbox/webhook-sink/" + chai.id(),
                                "Built-in sandbox receiver",
                                List.of(
                                        EventTypes.PAYMENT_SUCCEEDED,
                                        EventTypes.PAYMENT_REFUNDED,
                                        EventTypes.PAYMENT_FAILED,
                                        EventTypes.PAYOUT_PAID))));

        List<Purchase> catalogue = List.of(
                new Purchase(chai, "Masala chai subscription", 24_900, 99_900),
                new Purchase(chai, "Office snacks order", 45_000, 180_000),
                new Purchase(chai, "Cold brew & pastries", 18_000, 60_000),
                new Purchase(books, "Paperbacks", 39_900, 249_900),
                new Purchase(books, "Hardcover pre-order", 59_900, 159_900),
                new Purchase(pixel, "Design template pack", 99_900, 499_900),
                new Purchase(pixel, "Icon set licence", 49_900, 149_900));
        String[] notes = {
            "Dinner split", "Movie tickets", "Rent share", "Cab fare", "Birthday gift", "Groceries", "Concert tickets"
        };

        // ---- daily activity ---------------------------------------------------------------
        int seq = 0;
        List<UUID> refundable = new ArrayList<>();
        for (int day = 0; day < DAYS; day++) {
            Instant dayStart = start.plus(Duration.ofDays(day));
            boolean isToday = day == DAYS - 1;
            if (day % 7 == 0) {
                for (AuthenticatedUser c : customers) {
                    long amount = (c == asha ? 4_000_000 : 1_500_000) + random.nextInt(20) * 50_000L;
                    String key = "seed-topup-" + (seq++);
                    tryAt(
                            dayStart.plus(minutes(random, 0, 90)),
                            () -> wallets.topUp(c, key, new WalletDtos.TopUpRequest(amount, "INR")));
                }
            }
            int purchases = 2 + random.nextInt(isToday ? 2 : 4);
            for (int p = 0; p < purchases; p++) {
                AuthenticatedUser c = customers.get(random.nextInt(customers.size()));
                Purchase item = catalogue.get(random.nextInt(catalogue.size()));
                long amount = item.minAmount() + (random.nextLong(item.maxAmount() - item.minAmount()) / 100) * 100;
                if (random.nextInt(14) == 0) {
                    amount = (amount / 100) * 100 + 13; // sandbox decline rule: amounts ending in .13
                }
                long finalAmount = amount;
                String key = "seed-pay-" + (seq++);
                UUID id = tryAt(
                        dayStart.plus(minutes(random, 120, 900)),
                        () -> pay(c, item.merchant(), finalAmount, item.description(), key));
                if (id != null && random.nextInt(6) == 0) {
                    refundable.add(id);
                }
            }
            if (random.nextInt(3) != 0) {
                AuthenticatedUser from = customers.get(random.nextInt(customers.size()));
                AuthenticatedUser to = customers.get(
                        (customers.indexOf(from) + 1 + random.nextInt(customers.size() - 1)) % customers.size());
                long amount = (5 + random.nextInt(60)) * 10_000L;
                String note = notes[random.nextInt(notes.length)];
                String key = "seed-transfer-" + (seq++);
                tryAt(
                        dayStart.plus(minutes(random, 600, 1000)),
                        () -> transfers.create(
                                from, key, new TransferDtos.CreateTransferRequest(to.email(), amount, "INR", note)));
            }
            if (!refundable.isEmpty() && random.nextInt(4) == 0) {
                UUID paymentId = refundable.removeFirst();
                AuthenticatedUser merchant = merchantOf(paymentId, chai, books, pixel);
                boolean partial = random.nextBoolean();
                long partialAmount = 10_000L * (1 + random.nextInt(3));
                String key = "seed-refund-" + (seq++);
                tryAt(
                        dayStart.plus(minutes(random, 900, 1100)),
                        () -> refunds.create(
                                merchant,
                                paymentId,
                                key,
                                new RefundService.CreateRefundRequest(
                                        partial ? partialAmount : null,
                                        partial
                                                ? "Partial refund: item out of stock"
                                                : "Order cancelled by customer")));
            }
            if (day % 7 == 6) {
                for (AuthenticatedUser m : List.of(chai, books, pixel)) {
                    Instant when = dayStart.plus(Duration.ofHours(18));
                    long available = at(when, () -> merchants.balance(m).availableMinor());
                    if (available > 100_000) {
                        long amount = (available * 6 / 10) / 100 * 100;
                        String key = "seed-payout-" + (seq++);
                        tryAt(when, () -> merchants.payout(m, key, new MerchantDtos.PayoutRequest(amount)));
                    }
                }
            }
        }
        // One payment awaiting the customer's confirmation, created just now.
        payments.create(
                asha,
                "seed-pending",
                new PaymentDtos.CreatePaymentIntentRequest(
                        chai.id(), 32_000, "INR", "Awaiting confirmation", Map.of("orderId", "CC-1088")));

        log.info(
                "Demo data seeded ({} days of activity). Accounts use password '{}' (development only). Admin: {}",
                DAYS,
                DEMO_PASSWORD,
                admin.email());
    }

    private UUID pay(
            AuthenticatedUser customer, AuthenticatedUser merchant, long amount, String description, String key) {
        PaymentDtos.PaymentIntentView intent = payments.create(
                        customer,
                        key + "-create",
                        new PaymentDtos.CreatePaymentIntentRequest(merchant.id(), amount, "INR", description, Map.of()))
                .body();
        PaymentDtos.PaymentIntentView result =
                payments.confirm(customer, intent.id(), key + "-confirm").body();
        return "SUCCEEDED".equals(result.status()) ? intent.id() : null;
    }

    private AuthenticatedUser merchantOf(UUID paymentId, AuthenticatedUser... candidates) {
        for (AuthenticatedUser m : candidates) {
            try {
                payments.get(m, paymentId);
                return m;
            } catch (ApiException e) {
                // not this merchant's payment
            }
        }
        throw new IllegalStateException("Payment without merchant");
    }

    private AuthenticatedUser create(String email, String name, Role role, String business, String category) {
        User user = tx.execute(s -> provisioning.provision(new AccountProvisioningService.NewUser(
                email, name, DEMO_PASSWORD, role, Currency.INR, business, category)));
        return new AuthenticatedUser(user.getId(), user.getEmail(), role, UUID.randomUUID());
    }

    private <T> T at(Instant when, Supplier<T> action) {
        return clock.runAt(notInFuture(when), action);
    }

    /** Today's scheduled activity must not appear later than "now". */
    private static Instant notInFuture(Instant when) {
        Instant latest = Instant.now().minusSeconds(30);
        return when.isAfter(latest) ? latest : when;
    }

    /** Business-rule rejections (e.g. insufficient funds) are a normal part of random activity. */
    private <T> T tryAt(Instant when, Supplier<T> action) {
        try {
            return clock.runAt(notInFuture(when), action);
        } catch (ApiException e) {
            log.debug("Seed step skipped: {}", e.getMessage());
            return null;
        }
    }

    private static Duration minutes(Random random, int from, int to) {
        return Duration.ofMinutes(from + random.nextInt(Math.max(1, to - from)));
    }
}
