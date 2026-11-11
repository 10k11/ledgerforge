package dev.ledgerforge.common.money;

import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Integer-only money arithmetic. No floating point is ever used for monetary values: all inputs
 * and outputs are minor units, intermediate products use {@link BigInteger} so they cannot
 * overflow, and every rounding rule is explicit.
 */
public final class MoneyMath {

    public static final int BPS_DENOMINATOR = 10_000;

    private MoneyMath() {}

    /**
     * Platform fee for a gross amount, in basis points, rounded half-up to the nearest minor unit.
     * Example: 1,000.00 INR (100000 paise) at 200 bps = 2,000 paise (20.00 INR).
     */
    public static long feeForAmount(long amountMinor, int feeBps) {
        requireNonNegative(amountMinor, "amountMinor");
        if (feeBps < 0 || feeBps > BPS_DENOMINATOR) {
            throw new IllegalArgumentException("feeBps must be between 0 and 10000");
        }
        BigInteger product = BigInteger.valueOf(amountMinor).multiply(BigInteger.valueOf(feeBps));
        return new java.math.BigDecimal(product)
                .divide(java.math.BigDecimal.valueOf(BPS_DENOMINATOR), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /**
     * {@code floor(amount * numerator / denominator)} computed exactly. Used for proportional fee
     * reversals on partial refunds.
     */
    public static long proportionalFloor(long amount, long numerator, long denominator) {
        requireNonNegative(amount, "amount");
        requireNonNegative(numerator, "numerator");
        if (denominator <= 0) {
            throw new IllegalArgumentException("denominator must be positive");
        }
        return BigInteger.valueOf(amount)
                .multiply(BigInteger.valueOf(numerator))
                .divide(BigInteger.valueOf(denominator))
                .longValueExact();
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
