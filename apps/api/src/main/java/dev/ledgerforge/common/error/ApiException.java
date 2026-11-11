package dev.ledgerforge.common.error;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Domain exception rendered as an RFC 9457 problem. The detail message must be safe to show to
 * the API caller: never include secrets, stack traces or another user's data.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> properties;

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, Map.of());
    }

    public ApiException(ErrorCode code, String detail, Map<String, Object> properties) {
        super(detail);
        this.code = code;
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> properties() {
        return properties;
    }

    public static ApiException notFound(String resource) {
        return new ApiException(ErrorCode.NOT_FOUND, resource + " not found");
    }

    public static ApiException forbidden() {
        return new ApiException(ErrorCode.FORBIDDEN, "You do not have access to this resource");
    }

    public static ApiException invalidState(String detail) {
        return new ApiException(ErrorCode.INVALID_STATE_TRANSITION, detail);
    }

    public static ApiException rule(ErrorCode code, String detail) {
        return new ApiException(code, detail);
    }
}
