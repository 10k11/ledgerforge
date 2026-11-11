package dev.ledgerforge.transfer;

import dev.ledgerforge.audit.AuditLog;
import dev.ledgerforge.audit.AuditService;
import dev.ledgerforge.common.error.ApiException;
import dev.ledgerforge.common.error.ErrorCode;
import dev.ledgerforge.common.money.Currency;
import dev.ledgerforge.common.security.AuthenticatedUser;
import dev.ledgerforge.common.util.ReferenceGenerator;
import dev.ledgerforge.common.web.PageParams;
import dev.ledgerforge.common.web.PageResponse;
import dev.ledgerforge.idempotency.IdempotencyService;
import dev.ledgerforge.ledger.AccountPurpose;
import dev.ledgerforge.ledger.LedgerAccount;
import dev.ledgerforge.ledger.LedgerAccountService;
import dev.ledgerforge.ledger.LedgerPostingRequest;
import dev.ledgerforge.ledger.LedgerService;
import dev.ledgerforge.ledger.LedgerTransactionType;
import dev.ledgerforge.transfer.TransferDtos.CreateTransferRequest;
import dev.ledgerforge.transfer.TransferDtos.RecipientView;
import dev.ledgerforge.transfer.TransferDtos.TransferView;
import dev.ledgerforge.user.Role;
import dev.ledgerforge.user.User;
import dev.ledgerforge.user.UserDirectory;
import dev.ledgerforge.user.UserRepository;
import dev.ledgerforge.webhook.EventTypes;
import dev.ledgerforge.webhook.OutboxService;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Wallet-to-wallet transfers: Debit sender wallet, Credit recipient wallet. */
@Service
public class TransferService {

    private final TransferRepository transfers;
    private final UserRepository users;
    private final UserDirectory directory;
    private final LedgerAccountService accounts;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final OutboxService outbox;
    private final AuditService audit;
    private final Clock clock;

    public TransferService(
            TransferRepository transfers,
            UserRepository users,
            UserDirectory directory,
            LedgerAccountService accounts,
            LedgerService ledger,
            IdempotencyService idempotency,
            OutboxService outbox,
            AuditService audit,
            Clock clock) {
        this.transfers = transfers;
        this.users = users;
        this.directory = directory;
        this.accounts = accounts;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public IdempotencyService.Result<TransferView> create(
            AuthenticatedUser sender, String key, CreateTransferRequest request) {
        return idempotency.execute(
                new IdempotencyService.Scope(sender.id(), "POST", "/api/v1/transfers", key),
                request,
                TransferView.class,
                201,
                () -> doCreate(sender, key, request),
                TransferView::ledgerTransactionId);
    }

    private TransferView doCreate(AuthenticatedUser sender, String key, CreateTransferRequest request) {
        Currency currency = Currency.parse(request.currency());
        LedgerAccount senderWallet = accounts.findUserAccount(sender.id(), AccountPurpose.CUSTOMER_WALLET, currency)
                .orElseThrow(
                        () -> new ApiException(ErrorCode.CURRENCY_MISMATCH, "You do not have a wallet in " + currency));
        LedgerAccount recipientWallet = resolveRecipientWallet(request.recipient(), currency);
        if (recipientWallet.getOwnerId().equals(sender.id())) {
            throw new ApiException(ErrorCode.SELF_TRANSFER, "You cannot send money to yourself");
        }
        UUID transferId = UUID.randomUUID();
        String description =
                request.description() == null || request.description().isBlank()
                        ? "Wallet transfer"
                        : request.description().trim();
        // The ledger locks both balance rows in ascending account-id order and rejects the posting
        // if the sender's balance would go negative.
        LedgerService.PostedTransaction posted =
                ledger.post(LedgerPostingRequest.builder(LedgerTransactionType.TRANSFER, currency)
                        .description(description)
                        .debit(senderWallet.getId(), request.amountMinor())
                        .credit(recipientWallet.getId(), request.amountMinor())
                        .relatedEntity(transferId)
                        .idempotencyKey(key)
                        .initiatedBy(sender.id())
                        .build());
        Instant now = clock.instant();
        Transfer transfer = transfers.save(new Transfer(
                transferId,
                ReferenceGenerator.next("TRF"),
                sender.id(),
                recipientWallet.getOwnerId(),
                request.amountMinor(),
                currency,
                description,
                posted.id(),
                now));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("transferId", transfer.getId().toString());
        data.put("reference", transfer.getPublicReference());
        data.put("amountMinor", transfer.getAmountMinor());
        data.put("currency", currency.name());
        outbox.record(EventTypes.TRANSFER_COMPLETED, "TRANSFER", transfer.getId(), null, data);
        audit.record(
                sender,
                "TRANSFER_CREATED",
                "TRANSFER",
                transfer.getId(),
                AuditLog.Result.SUCCESS,
                Map.of("amountMinor", request.amountMinor(), "currency", currency.name()));
        String counterparty = directory
                .lookup(List.of(transfer.getRecipientId()))
                .get(transfer.getRecipientId())
                .displayName();
        return view(
                transfer,
                sender.id(),
                UserDirectory.shortName(counterparty),
                posted.balanceAfter(senderWallet.getId()));
    }

    private LedgerAccount resolveRecipientWallet(String recipient, Currency currency) {
        String needle = recipient.trim();
        if (needle.contains("@")) {
            User user = users.findByEmail(needle.toLowerCase())
                    .filter(u -> u.getRole() == Role.CUSTOMER && u.getStatus() == User.Status.ACTIVE)
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.RECIPIENT_NOT_FOUND, "No customer wallet matches this recipient"));
            return accounts.findUserAccount(user.getId(), AccountPurpose.CUSTOMER_WALLET, currency)
                    .orElseThrow(() ->
                            new ApiException(ErrorCode.CURRENCY_MISMATCH, "The recipient cannot receive " + currency));
        }
        LedgerAccount account = accounts.findByAccountNumber(needle.toUpperCase())
                .filter(a -> a.getPurpose() == AccountPurpose.CUSTOMER_WALLET)
                .orElseThrow(() ->
                        new ApiException(ErrorCode.RECIPIENT_NOT_FOUND, "No customer wallet matches this recipient"));
        if (account.getCurrency() != currency) {
            throw new ApiException(
                    ErrorCode.CURRENCY_MISMATCH, "The recipient wallet is denominated in " + account.getCurrency());
        }
        return account;
    }

    /** Confirms who will receive a transfer before the sender commits, with minimal disclosure. */
    @Transactional(readOnly = true)
    public RecipientView lookupRecipient(AuthenticatedUser sender, String recipient) {
        LedgerAccount senderWallet = accounts.accountsOwnedBy(sender.id()).stream()
                .filter(a -> a.getPurpose() == AccountPurpose.CUSTOMER_WALLET)
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Wallet"));
        LedgerAccount wallet = resolveRecipientWallet(recipient, senderWallet.getCurrency());
        if (wallet.getOwnerId().equals(sender.id())) {
            throw new ApiException(ErrorCode.SELF_TRANSFER, "You cannot send money to yourself");
        }
        UserDirectory.Party party =
                directory.lookup(List.of(wallet.getOwnerId())).get(wallet.getOwnerId());
        String number = wallet.getAccountNumber();
        return new RecipientView(
                UserDirectory.shortName(party.displayName()),
                party.maskedEmail(),
                "••••" + number.substring(number.length() - 4),
                wallet.getCurrency().name());
    }

    @Transactional(readOnly = true)
    public PageResponse<TransferView> list(
            AuthenticatedUser user, String direction, Instant from, Instant to, String q, PageParams page) {
        String dir = direction == null ? "ALL" : direction.toUpperCase();
        if (!List.of("ALL", "SENT", "RECEIVED").contains(dir)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "direction must be ALL, SENT or RECEIVED");
        }
        Page<Transfer> result = transfers.search(
                dir,
                user.id(),
                from == null ? Instant.EPOCH : from,
                to == null ? clock.instant().plusSeconds(60) : to,
                q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%",
                PageRequest.of(page.page(), page.size(), Sort.by(Sort.Direction.DESC, "createdAt")));
        Map<UUID, UserDirectory.Party> parties = directory.lookup(result.getContent().stream()
                .map(t -> t.getSenderId().equals(user.id()) ? t.getRecipientId() : t.getSenderId())
                .toList());
        return PageResponse.from(result, t -> {
            UUID other = t.getSenderId().equals(user.id()) ? t.getRecipientId() : t.getSenderId();
            UserDirectory.Party party = parties.get(other);
            return view(t, user.id(), party == null ? "Unknown" : UserDirectory.shortName(party.displayName()), null);
        });
    }

    @Transactional(readOnly = true)
    public TransferView get(AuthenticatedUser user, UUID id) {
        Transfer t = transfers
                .findById(id)
                .filter(x ->
                        x.getSenderId().equals(user.id()) || x.getRecipientId().equals(user.id()))
                // 404 (not 403) so the existence of other users' transfers is not revealed
                .orElseThrow(() -> ApiException.notFound("Transfer"));
        UUID other = t.getSenderId().equals(user.id()) ? t.getRecipientId() : t.getSenderId();
        UserDirectory.Party party = directory.lookup(List.of(other)).get(other);
        return view(t, user.id(), UserDirectory.shortName(party.displayName()), null);
    }

    private static TransferView view(Transfer t, UUID viewer, String counterparty, Long balanceAfter) {
        return new TransferView(
                t.getId(),
                t.getPublicReference(),
                t.getSenderId().equals(viewer) ? "SENT" : "RECEIVED",
                counterparty,
                t.getAmountMinor(),
                t.getCurrency().name(),
                t.getDescription(),
                t.getStatus(),
                t.getLedgerTransactionId(),
                balanceAfter,
                t.getCreatedAt());
    }
}
