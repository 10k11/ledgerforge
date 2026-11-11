package dev.ledgerforge.ledger;

import dev.ledgerforge.common.error.ApiException;
import dev.ledgerforge.common.error.ErrorCode;
import java.util.Map;

/** Thrown when a posting request would break a ledger invariant. Nothing is persisted. */
public class LedgerInvariantViolation extends ApiException {

    private final String invariant;

    public LedgerInvariantViolation(String invariant, String detail) {
        super(ErrorCode.LEDGER_INVARIANT_VIOLATION, detail, Map.of("invariant", invariant));
        this.invariant = invariant;
    }

    public String invariant() {
        return invariant;
    }
}
