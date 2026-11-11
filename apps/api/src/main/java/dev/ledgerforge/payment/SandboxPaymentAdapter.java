package dev.ledgerforge.payment;

import org.springframework.stereotype.Component;

/**
 * Deterministic sandbox processor, so failure paths are testable on demand:
 *
 * <ul>
 *   <li>amount whose minor part is {@code .13} (e.g. 250.13) &rarr; declined {@code risk_declined}
 *   <li>amount whose minor part is {@code .14} &rarr; declined {@code processor_unavailable}
 *   <li>anything else &rarr; approved (wallet balance is still enforced by the ledger)
 * </ul>
 */
@Component
public class SandboxPaymentAdapter implements PaymentAdapter {

    @Override
    public Decision authorize(PaymentIntent intent) {
        long cents = intent.getAmountMinor() % 100;
        if (cents == 13) {
            return new Declined("risk_declined", "Declined by the sandbox risk rule (amounts ending in .13)");
        }
        if (cents == 14) {
            return new Declined("processor_unavailable", "Sandbox processor unavailable (amounts ending in .14)");
        }
        return new Approved();
    }
}
