package dev.ledgerforge.common.money;

import dev.ledgerforge.common.error.ApiException;
import dev.ledgerforge.common.error.ErrorCode;

/** Supported ISO-4217 currencies. All amounts are integer minor units (2 decimals for both). */
public enum Currency {
    INR(2, "₹"),
    USD(2, "$");

    private final int minorDigits;
    private final String symbol;

    Currency(int minorDigits, String symbol) {
        this.minorDigits = minorDigits;
        this.symbol = symbol;
    }

    public int minorDigits() {
        return minorDigits;
    }

    public String symbol() {
        return symbol;
    }

    public static Currency parse(String code) {
        if (code == null) {
            throw new ApiException(ErrorCode.UNSUPPORTED_CURRENCY, "Currency is required");
        }
        try {
            return Currency.valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(ErrorCode.UNSUPPORTED_CURRENCY, "Currency " + code + " is not supported");
        }
    }
}
