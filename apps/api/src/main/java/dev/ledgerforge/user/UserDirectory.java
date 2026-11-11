package dev.ledgerforge.user;

import dev.ledgerforge.merchant.MerchantProfile;
import dev.ledgerforge.merchant.MerchantProfileRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves display information about other parties with minimal disclosure: names and masked
 * emails only. Used when one user needs to see who the counterparty of a transaction was.
 */
@Service
public class UserDirectory {

    private final UserRepository users;
    private final MerchantProfileRepository merchants;

    public UserDirectory(UserRepository users, MerchantProfileRepository merchants) {
        this.users = users;
        this.merchants = merchants;
    }

    public record Party(UUID id, String displayName, String maskedEmail, Role role) {}

    @Transactional(readOnly = true)
    public Map<UUID, Party> lookup(Collection<UUID> ids) {
        Map<UUID, Party> out = new HashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        List<UUID> distinct = ids.stream().distinct().toList();
        Map<UUID, String> businessNames = new HashMap<>();
        for (MerchantProfile m : merchants.findByUserIdIn(distinct)) {
            businessNames.put(m.getUserId(), m.getBusinessName());
        }
        for (User u : users.findAllById(distinct)) {
            String name = u.getRole() == Role.MERCHANT
                    ? businessNames.getOrDefault(u.getId(), u.getFullName())
                    : u.getFullName();
            out.put(u.getId(), new Party(u.getId(), name, maskEmail(u.getEmail()), u.getRole()));
        }
        return out;
    }

    /** "asha.verma@example.com" becomes "as•••@example.com". */
    public static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "•••";
        }
        String local = email.substring(0, at);
        String visible = local.length() <= 2 ? local.substring(0, 1) : local.substring(0, 2);
        return visible + "•••" + email.substring(at);
    }

    /** "Asha Verma" becomes "Asha V." */
    public static String shortName(String fullName) {
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0];
        }
        return parts[0] + " " + parts[parts.length - 1].charAt(0) + ".";
    }
}
