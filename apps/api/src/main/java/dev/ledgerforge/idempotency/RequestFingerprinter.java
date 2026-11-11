package dev.ledgerforge.idempotency;

import dev.ledgerforge.common.util.Hashing;
import org.springframework.stereotype.Component;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deterministic request fingerprint: SHA-256 over {@code METHOD \n ENDPOINT \n canonical-json}.
 * Canonical JSON sorts object properties and map keys so semantically identical bodies hash the
 * same regardless of field order.
 */
@Component
public class RequestFingerprinter {

    private final JsonMapper canonical = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    public String fingerprint(String method, String endpoint, Object body) {
        String json = body == null ? "null" : canonical.writeValueAsString(body);
        return Hashing.sha256Hex(method + "\n" + endpoint + "\n" + json);
    }
}
