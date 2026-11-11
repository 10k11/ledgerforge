package dev.ledgerforge.payment;

import static dev.ledgerforge.payment.PaymentStatus.CANCELLED;
import static dev.ledgerforge.payment.PaymentStatus.CREATED;
import static dev.ledgerforge.payment.PaymentStatus.FAILED;
import static dev.ledgerforge.payment.PaymentStatus.PARTIALLY_REFUNDED;
import static dev.ledgerforge.payment.PaymentStatus.PROCESSING;
import static dev.ledgerforge.payment.PaymentStatus.REFUNDED;
import static dev.ledgerforge.payment.PaymentStatus.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ledgerforge.common.error.ApiException;
import dev.ledgerforge.common.error.ErrorCode;
import dev.ledgerforge.common.money.Currency;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PaymentStatusTest {

    @ParameterizedTest(name = "{0} -> {1} allowed={2}")
    @CsvSource({
        "CREATED, PROCESSING, true",
        "CREATED, CANCELLED, true",
        "CREATED, SUCCEEDED, false",
        "PROCESSING, SUCCEEDED, true",
        "PROCESSING, FAILED, true",
        "PROCESSING, CANCELLED, false",
        "SUCCEEDED, PARTIALLY_REFUNDED, true",
        "SUCCEEDED, REFUNDED, true",
        "SUCCEEDED, CANCELLED, false",
        "SUCCEEDED, PROCESSING, false",
        "PARTIALLY_REFUNDED, PARTIALLY_REFUNDED, true",
        "PARTIALLY_REFUNDED, REFUNDED, true",
        "REFUNDED, PARTIALLY_REFUNDED, false",
        "FAILED, PROCESSING, false",
        "CANCELLED, PROCESSING, false"
    })
    void transitions(PaymentStatus from, PaymentStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @Test
    void terminalStates() {
        assertThat(FAILED.isTerminal()).isTrue();
        assertThat(CANCELLED.isTerminal()).isTrue();
        assertThat(REFUNDED.isTerminal()).isTrue();
        assertThat(SUCCEEDED.isTerminal()).isFalse();
        assertThat(CREATED.isCaptured()).isFalse();
        assertThat(PARTIALLY_REFUNDED.isCaptured()).isTrue();
    }

    @Test
    void entityRejectsIllegalTransitionAndRefundOverflow() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        PaymentIntent intent = new PaymentIntent(
                UUID.randomUUID(),
                "PAY_TEST",
                UUID.randomUUID(),
                UUID.randomUUID(),
                100_000,
                Currency.INR,
                200,
                2_000,
                null,
                "{}",
                now);
        assertThatThrownBy(() -> intent.markSucceeded(UUID.randomUUID(), now))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code())
                .isEqualTo(ErrorCode.INVALID_STATE_TRANSITION);

        intent.transitionTo(PROCESSING, now);
        intent.markSucceeded(UUID.randomUUID(), now);
        assertThat(intent.refundableAmountMinor()).isEqualTo(100_000);
        intent.applyRefund(40_000, 800, now);
        assertThat(intent.getStatus()).isEqualTo(PARTIALLY_REFUNDED);
        intent.applyRefund(60_000, 1_200, now);
        assertThat(intent.getStatus()).isEqualTo(REFUNDED);
        assertThat(intent.refundableAmountMinor()).isZero();
        assertThatThrownBy(() -> intent.applyRefund(1, 0, now)).isInstanceOf(IllegalStateException.class);
        assertThat(intent.getStatus()).isEqualTo(REFUNDED);
        assertThat(SUCCEEDED.canTransitionTo(REFUNDED)).isTrue();
    }
}
