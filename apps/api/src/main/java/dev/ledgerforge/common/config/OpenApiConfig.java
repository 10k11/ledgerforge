package dev.ledgerforge.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ledgerForgeOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("LedgerForge API")
                        .version("v1")
                        .description("""
                                Payment infrastructure sandbox built on a double-entry ledger.

                                * All amounts are integers in minor units (paise / cents) with an explicit currency.
                                * Money-moving POST endpoints require an `Idempotency-Key` header. Replays return the
                                  original response with `Idempotent-Replayed: true`; reusing a key with a different
                                  body returns `409 IDEMPOTENCY_KEY_REUSED`.
                                * Errors use RFC 9457 Problem Details (`application/problem+json`) with a stable
                                  `code` and a `correlationId` matching the `X-Request-Id` response header.
                                * Educational sandbox only: no real money, cards or bank credentials.
                                """)
                        .license(new License().name("MIT")))
                .components(new Components()
                        .addSecuritySchemes(
                                "bearer",
                                new SecurityScheme()
                                        .type(SecurityScheme.Type.HTTP)
                                        .scheme("bearer")
                                        .bearerFormat("JWT")
                                        .description("Access token from /api/v1/auth/login (10 minute lifetime)")));
    }
}
