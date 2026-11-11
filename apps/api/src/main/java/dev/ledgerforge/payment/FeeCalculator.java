package dev.ledgerforge.payment;

import dev.ledgerforge.common.config.AppProperties;
import dev.ledgerforge.common.money.MoneyMath;
import org.springframework.stereotype.Component;

/**
 * Fee policy (illustrative, configurable via {@code PLATFORM_FEE_BPS}, default 200 bps = 2%).
 *
 * <ul>
 *   <li>Capture: {@code fee = round_half_up(amount * bps / 10000)}, {@code net = amount - fee}.
 *       The fee rate is snapshotted on the intent at creation time.
 *   <li>Refunds use <em>cumulative</em> rounding: after a refund bringing the refunded total to
 *       {@code R}, the total fee reversed is {@code floor(R * fee / amount)}; the refund's fee
 *       reversal is the difference from what was reversed before. When {@code R == amount} this is
 *       exactly {@code fee}, so any sequence of partial refunds reconciles to the cent with the
 *       original payment and no step can ever produce a negative merchant debit.
 * </ul>
 */
@Component
public class FeeCalculator {

    private final int feeBps;

    public FeeCalculator(AppProperties props) {
        this.feeBps = props.payments().platformFeeBps();
    }

    public int currentFeeBps() {
        return feeBps;
    }

    public long platformFee(long amountMinor, int bps) {
        return MoneyMath.feeForAmount(amountMinor, bps);
    }

    public record RefundSplit(long feeReversalMinor, long merchantDebitMinor) {}

    public static RefundSplit refundSplit(
            long paymentAmountMinor,
            long paymentFeeMinor,
            long alreadyRefundedMinor,
            long alreadyReversedFeeMinor,
            long refundAmountMinor) {
        long refundedAfter = Math.addExact(alreadyRefundedMinor, refundAmountMinor);
        if (refundAmountMinor <= 0 || refundedAfter > paymentAmountMinor) {
            throw new IllegalArgumentException("Refund amount out of range");
        }
        long cumulativeFee = MoneyMath.proportionalFloor(refundedAfter, paymentFeeMinor, paymentAmountMinor);
        long feeReversal = cumulativeFee - alreadyReversedFeeMinor;
        return new RefundSplit(feeReversal, refundAmountMinor - feeReversal);
    }
}
