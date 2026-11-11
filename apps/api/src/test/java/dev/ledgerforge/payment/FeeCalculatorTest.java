package dev.ledgerforge.payment;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ledgerforge.common.money.MoneyMath;
import java.util.Random;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class FeeCalculatorTest {

    @Test
    void fullRefundReversesTheWholeFee() {
        FeeCalculator.RefundSplit split = FeeCalculator.refundSplit(100_000, 2_000, 0, 0, 100_000);
        assertThat(split.feeReversalMinor()).isEqualTo(2_000);
        assertThat(split.merchantDebitMinor()).isEqualTo(98_000);
    }

    @Test
    void partialRefundReversesProportionalFee() {
        FeeCalculator.RefundSplit split = FeeCalculator.refundSplit(100_000, 2_000, 0, 0, 30_000);
        assertThat(split.feeReversalMinor()).isEqualTo(600);
        assertThat(split.merchantDebitMinor()).isEqualTo(29_400);
    }

    @Test
    void tinyRefundsNeverProduceNegativeMerchantDebit() {
        // amount 100, fee 2: one-unit refunds. Naive per-refund flooring would leave the final
        // refund needing a fee reversal larger than the refund itself.
        long refunded = 0;
        long feeReversed = 0;
        for (int i = 0; i < 100; i++) {
            FeeCalculator.RefundSplit s = FeeCalculator.refundSplit(100, 2, refunded, feeReversed, 1);
            assertThat(s.merchantDebitMinor()).isGreaterThanOrEqualTo(0);
            assertThat(s.feeReversalMinor()).isGreaterThanOrEqualTo(0);
            refunded += 1;
            feeReversed += s.feeReversalMinor();
        }
        assertThat(feeReversed).isEqualTo(2);
    }

    /** Any sequence of partial refunds reconciles exactly with the original capture. */
    @RepeatedTest(200)
    void randomRefundSequencesReconcileExactly() {
        Random random = new Random();
        long amount = 1 + random.nextInt(10_000_000);
        int bps = random.nextInt(10_001);
        long fee = MoneyMath.feeForAmount(amount, bps);
        long net = amount - fee;
        long refunded = 0;
        long feeReversed = 0;
        long merchantDebited = 0;
        while (refunded < amount) {
            long remaining = amount - refunded;
            long refund = random.nextInt(4) == 0 ? remaining : 1 + (long) (random.nextDouble() * remaining);
            refund = Math.min(refund, remaining);
            FeeCalculator.RefundSplit s = FeeCalculator.refundSplit(amount, fee, refunded, feeReversed, refund);
            assertThat(s.feeReversalMinor()).isBetween(0L, refund);
            assertThat(s.feeReversalMinor() + s.merchantDebitMinor()).isEqualTo(refund);
            refunded += refund;
            feeReversed += s.feeReversalMinor();
            merchantDebited += s.merchantDebitMinor();
            assertThat(feeReversed).isLessThanOrEqualTo(fee);
            assertThat(merchantDebited).isLessThanOrEqualTo(net);
        }
        assertThat(feeReversed).isEqualTo(fee);
        assertThat(merchantDebited).isEqualTo(net);
    }
}
