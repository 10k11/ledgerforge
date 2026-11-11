package dev.ledgerforge.dashboard;

import dev.ledgerforge.common.config.AppProperties;
import dev.ledgerforge.common.money.Currency;
import dev.ledgerforge.common.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Arrays;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Overview", description = "Dashboard metrics and platform metadata")
public class DashboardController {

    private final DashboardService dashboard;
    private final AppProperties props;

    public DashboardController(DashboardService dashboard, AppProperties props) {
        this.dashboard = dashboard;
        this.props = props;
    }

    @GetMapping("/api/v1/dashboard/summary")
    @SecurityRequirement(name = "bearer")
    @Operation(summary = "Balance, totals by transaction type and 30-day inflow/outflow for the caller's account")
    public DashboardService.Summary summary(@AuthenticationPrincipal AuthenticatedUser user) {
        return dashboard.summary(user);
    }

    public record Meta(
            String environment,
            boolean sandboxEnabled,
            int platformFeeBps,
            List<String> currencies,
            long maxTopUpMinor,
            long dailyTopUpLimitMinor,
            String sandboxReceiverBaseUrl) {}

    @GetMapping("/api/v1/meta")
    @Operation(summary = "Public platform configuration relevant to clients")
    public Meta meta() {
        return new Meta(
                props.env(),
                props.sandbox().enabled(),
                props.payments().platformFeeBps(),
                Arrays.stream(Currency.values()).map(Enum::name).toList(),
                props.sandbox().maxTopUpMinor(),
                props.sandbox().dailyTopUpLimitMinor(),
                props.sandbox().enabled() ? props.selfBaseUrl() + "/api/v1/sandbox/webhook-sink/" : null);
    }
}
