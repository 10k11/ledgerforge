package dev.ledgerforge.auth;

import dev.ledgerforge.common.error.ApiException;
import dev.ledgerforge.common.error.ErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Password rules: 10-64 characters (BCrypt only considers the first 72 bytes), at least one letter
 * and one digit, not the email address, and not a well-known weak password.
 */
public final class PasswordPolicy {

    private static final Set<String> COMMON = Set.of(
            "password123",
            "password1234",
            "qwerty12345",
            "1234567890",
            "letmein1234",
            "welcome123",
            "admin12345",
            "iloveyou123",
            "passw0rd123",
            "1q2w3e4r5t");

    private PasswordPolicy() {}

    public static void validate(String password, String email, String field) {
        List<String> problems = problems(password, email);
        if (!problems.isEmpty()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Password does not meet requirements",
                    Map.of(
                            "errors",
                            problems.stream()
                                    .map(p -> Map.of("field", field, "message", p))
                                    .toList()));
        }
    }

    static List<String> problems(String password, String email) {
        List<String> problems = new ArrayList<>();
        if (password == null || password.length() < 10) {
            problems.add("must be at least 10 characters");
            return problems;
        }
        if (password.length() > 64 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            problems.add("must be at most 64 characters");
        }
        if (password.chars().noneMatch(Character::isLetter)) {
            problems.add("must contain a letter");
        }
        if (password.chars().noneMatch(Character::isDigit)) {
            problems.add("must contain a digit");
        }
        if (email != null && password.equalsIgnoreCase(email)) {
            problems.add("must not equal your email address");
        }
        if (COMMON.contains(password.toLowerCase())) {
            problems.add("is too common");
        }
        return problems;
    }
}
