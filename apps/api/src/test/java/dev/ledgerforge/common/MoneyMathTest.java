package dev.ledgerforge.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerforge.common.money.MoneyMath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MoneyMathTest {

    @ParameterizedTest(name = "{0} minor at {1} bps = {2}")
    @CsvSource({
        "100000, 200, 2000", // 1,000.00 at 2% = 20.00
        "1, 200, 0", // 0.02 rounds half-up to 0
        "25, 200, 1", // 0.5 rounds half-up to 1
        "24, 200, 0", // 0.48 rounds to 0
        "99999, 250, 2500", // 2499.975 -> 2500
        "0, 200, 0",
        "100000, 0, 0",
        "100000, 10000, 100000"
    })
    void feeIsRoundedHalfUp(long amount, int bps, long expected) {
        assertThat(MoneyMath.feeForAmount(amount, bps)).isEqualTo(expected);
    }

    @Test
    void feeNeverOverflowsForLargeAmounts() {
        long huge = 9_000_000_000_000_000L;
        assertThat(MoneyMath.feeForAmount(huge, 10_000)).isEqualTo(huge);
    }

    @Test
    void proportionalFloorUsesExactIntegerArithmetic() {
        assertThat(MoneyMath.proportionalFloor(30_000, 2_000, 100_000)).isEqualTo(600);
        assertThat(MoneyMath.proportionalFloor(1, 2, 3)).isEqualTo(0);
        // Product exceeds Long.MAX_VALUE but the result fits.
        assertThat(MoneyMath.proportionalFloor(Long.MAX_VALUE / 2, 4, 4)).isEqualTo(Long.MAX_VALUE / 2);
    }

    @Test
    void rejectsInvalidInput() {
        assertThatThrownBy(() -> MoneyMath.feeForAmount(-1, 200)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.feeForAmount(1, 10_001)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoneyMath.proportionalFloor(1, 1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
