package dev.ledgerforge.admin;

import dev.ledgerforge.audit.AuditLog;
import dev.ledgerforge.audit.AuditLogRepository;
import dev.ledgerforge.common.security.AuthenticatedUser;
import dev.ledgerforge.common.web.PageParams;
import dev.ledgerforge.common.web.PageResponse;
import dev.ledgerforge.idempotency.IdempotentResponses;
import dev.ledgerforge.ledger.ReconciliationService;
import dev.ledgerforge.user.User;
import dev.ledgerforge.user.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operations console. URL-level rule restricts /api/v1/admin/** to ROLE_ADMIN. */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin", description = "Ledger reconciliation, platform overview, audit log and corrections")
@SecurityRequirement(name = "bearer")
public class AdminController {

    private final ReconciliationService reconciliation;
    private final AdminService admin;
    private final AuditLogRepository auditLogs;
    private final UserRepository users;

    public AdminController(
            ReconciliationService reconciliation,
            AdminService admin,
            AuditLogRepository auditLogs,
            UserRepository users) {
        this.reconciliation = reconciliation;
        this.admin = admin;
        this.auditLogs = auditLogs;
        this.users = users;
    }

    @GetMapping("/reconciliation")
    @Operation(summary = "Verify balances against ledger entries, per-transaction balance and the accounting equation")
    public ReconciliationService.Report reconcile() {
        return reconciliation.reconcile();
    }

    @GetMapping("/overview")
    @Operation(summary = "Platform totals and system account balances")
    public AdminService.Overview overview() {
        return admin.overview();
    }

    public record AuditView(
            UUID id,
            UUID actorId,
            String actorRole,
            String action,
            String resourceType,
            String resourceId,
            String result,
            String correlationId,
            String metadata,
            Instant createdAt) {}

    @GetMapping("/audit-logs")
    @Operation(summary = "Audit log (newest first)")
    @Transactional(readOnly = true)
    public PageResponse<AuditView> audit(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) UUID actorId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        PageParams p = PageParams.of(page, size);
        return PageResponse.from(
                auditLogs.search(
                        action == null || action.isBlank()
                                ? null
                                : action.trim().toUpperCase(),
                        actorId,
                        PageRequest.of(p.page(), p.size())),
                (AuditLog a) -> new AuditView(
                        a.getId(),
                        a.getActorId(),
                        a.getActorRole(),
                        a.getAction(),
                        a.getResourceType(),
                        a.getResourceId(),
                        a.getResult(),
                        a.getCorrelationId(),
                        a.getMetadata(),
                        a.getCreatedAt()));
    }

    public record UserView(
            UUID id,
            String email,
            String fullName,
            String role,
            String status,
            Instant createdAt,
            Instant lastLoginAt) {}

    @GetMapping("/users")
    @Operation(summary = "Search users")
    @Transactional(readOnly = true)
    public PageResponse<UserView> users(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        PageParams p = PageParams.of(page, size);
        return PageResponse.from(
                users.search(
                        q == null || q.isBlank() ? null : "%" + q.trim().toLowerCase() + "%",
                        PageRequest.of(p.page(), p.size())),
                (User u) -> new UserView(
                        u.getId(),
                        u.getEmail(),
                        u.getFullName(),
                        u.getRole().name(),
                        u.getStatus().name(),
                        u.getCreatedAt(),
                        u.getLastLoginAt()));
    }

    public record ReversalRequest(@NotBlank @Size(max = 255) String reason) {}

    @PostMapping("/ledger/transactions/{transactionId}/reversal")
    @Operation(
            summary = "Post a full reversal of a top-up or transfer (correction)",
            description = "Ledger history is never edited: corrections are new, balanced transactions that reference"
                    + " the original. Payments must be refunded instead. Requires Idempotency-Key.")
    public ResponseEntity<AdminService.ReversalView> reverse(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID transactionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ReversalRequest request) {
        return IdempotentResponses.of(admin.reverse(user, transactionId, idempotencyKey, request.reason()));
    }

    @GetMapping("/system-accounts")
    @Operation(summary = "System ledger accounts with balances")
    public List<AdminService.AccountView> systemAccounts() {
        return admin.overview().systemAccounts();
    }
}
