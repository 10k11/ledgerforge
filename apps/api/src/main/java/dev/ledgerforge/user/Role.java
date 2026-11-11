package dev.ledgerforge.user;

public enum Role {
    CUSTOMER,
    MERCHANT,
    ADMIN;

    public String authority() {
        return "ROLE_" + name();
    }
}
