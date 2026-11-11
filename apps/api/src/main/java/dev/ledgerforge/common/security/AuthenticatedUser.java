package dev.ledgerforge.common.security;

import dev.ledgerforge.user.Role;
import java.util.UUID;

/**
 * The authenticated principal, derived exclusively from a verified access token. Services take
 * identity from here and never from request bodies or query parameters.
 */
public record AuthenticatedUser(UUID id, String email, Role role, UUID sessionId) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    public boolean isMerchant() {
        return role == Role.MERCHANT;
    }

    public boolean isCustomer() {
        return role == Role.CUSTOMER;
    }
}
