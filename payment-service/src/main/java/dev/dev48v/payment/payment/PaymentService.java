package dev.dev48v.payment.payment;

import dev.dev48v.payment.events.PaymentEventProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

// Day 27 — the payment decision. This stands in for a real payment-gateway call (Stripe, Adyen, …) with a
// DETERMINISTIC simulation, on purpose: a real gateway is non-deterministic and needs network + secrets,
// which would make the @EmbeddedKafka test flaky and non-hermetic. A pure function of the order's data keeps
// the event round-trip repeatable and CI-safe; swapping in a real gateway client later changes only THIS
// method, not the listener or the events around it.
//
// The rule set (all knobs come from PaymentEventProperties, so they retune per environment without a
// recompile):
//   1. A "test card" — an order from the configured decline-customer — is always DECLINED. This gives the
//      decline path a trivially reproducible trigger independent of amount.
//   2. Otherwise, an amount OVER the decline threshold is DECLINED as over-limit.
//   3. Everything else is APPROVED.
// The order carries no money field, so the charge AMOUNT is derived deterministically as unitPrice × quantity.
@Service
public class PaymentService {

    private final PaymentEventProperties properties;

    public PaymentService(PaymentEventProperties properties) {
        this.properties = properties;
    }

    // Turn an order line (quantity of some item) into a charge amount using the configured flat unit price.
    // BigDecimal throughout — money never touches double.
    public BigDecimal amountFor(int quantity) {
        return properties.unitPrice().multiply(BigDecimal.valueOf(quantity));
    }

    // Decide the payment for an order. Returns a DECIDED Payment (APPROVED or DECLINED, never left PENDING).
    public Payment process(String orderId, String customer, BigDecimal amount) {
        Payment payment = Payment.pending(orderId, customer, amount);

        if (isTestCard(customer)) {
            // A known test-card customer always fails — a deterministic decline trigger for demos/tests.
            payment.decline("TEST_CARD_DECLINED");
        } else if (amount.compareTo(properties.declineThreshold()) > 0) {
            // Over the limit: the charge is too large to auto-approve in this simulation.
            payment.decline("AMOUNT_OVER_LIMIT");
        } else {
            payment.approve("APPROVED");
        }
        return payment;
    }

    private boolean isTestCard(String customer) {
        return customer != null
                && customer.trim().equalsIgnoreCase(properties.declineCustomer());
    }
}
