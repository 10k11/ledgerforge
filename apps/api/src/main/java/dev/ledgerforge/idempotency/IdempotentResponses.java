package dev.ledgerforge.idempotency;

import org.springframework.http.ResponseEntity;

/** Converts an idempotent result to an HTTP response, flagging replays for client visibility. */
public final class IdempotentResponses {

    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    private IdempotentResponses() {}

    public static <T> ResponseEntity<T> of(IdempotencyService.Result<T> result) {
        return ResponseEntity.status(result.status())
                .header(REPLAYED_HEADER, String.valueOf(result.replayed()))
                .body(result.body());
    }
}
