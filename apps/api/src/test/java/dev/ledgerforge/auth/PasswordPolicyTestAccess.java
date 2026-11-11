package dev.ledgerforge.auth;

import java.util.List;

/** Exposes the package-private password rule evaluation to tests in other packages. */
public final class PasswordPolicyTestAccess {

    private PasswordPolicyTestAccess() {}

    public static List<String> problems(String password, String email) {
        return PasswordPolicy.problems(password, email);
    }
}
