package dev.dev48v.payment.payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// Day 27 — the payment context's core domain object: ONE payment attempt for one order. Like
// inventory-service's StockItem, this is a class WITH behaviour rather than a bare data record, because a
// rule lives with the data it guards: a payment is decided exactly ONCE. It is created PENDING and then
// transitions a single time to APPROVED or DECLINED; approve()/decline() refuse to run on an
// already-decided payment. That invariant is what makes re-processing (a Kafka redelivery slipping past the
// idempotency guard, say) unable to silently flip an outcome or double-charge.
//
// This is the Payment context's OWN model — it looks nothing like Order or StockItem, and that is correct:
// each bounded context models the world in the terms that matter to IT. Payments care about an amount, a
// decision, and a reason. Amounts are BigDecimal, never double — money must not carry binary floating-point
// rounding error.
public class Payment {

    private final String paymentId;   // this attempt's own id
    private final String orderId;     // the order being paid for — the idempotency key
    private final String customer;    // who is being charged
    private final BigDecimal amount;  // how much (derived from the order; BigDecimal for exactness)
    private PaymentStatus status;     // PENDING -> APPROVED | DECLINED (decided once)
    private String reason;            // machine-readable outcome reason
    private final Instant createdAt;

    private Payment(String orderId, String customer, BigDecimal amount) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId must not be blank");
        }
        if (amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be null or negative");
        }
        this.paymentId = UUID.randomUUID().toString();
        this.orderId = orderId;
        this.customer = customer;
        this.amount = amount;
        this.status = PaymentStatus.PENDING;
        this.createdAt = Instant.now();
    }

    // Create a fresh, undecided payment for an order.
    public static Payment pending(String orderId, String customer, BigDecimal amount) {
        return new Payment(orderId, customer, amount);
    }

    // Approve the payment. Legal only from PENDING — a decided payment cannot be re-decided.
    public void approve(String reason) {
        requirePending();
        this.status = PaymentStatus.APPROVED;
        this.reason = reason;
    }

    // Decline the payment. Legal only from PENDING — symmetric with approve().
    public void decline(String reason) {
        requirePending();
        this.status = PaymentStatus.DECLINED;
        this.reason = reason;
    }

    private void requirePending() {
        if (this.status != PaymentStatus.PENDING) {
            throw new IllegalStateException(
                    "payment for order " + orderId + " already decided: " + status);
        }
    }

    public String getPaymentId() {
        return paymentId;
    }

    public String getOrderId() {
        return orderId;
    }

    public String getCustomer() {
        return customer;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isApproved() {
        return status == PaymentStatus.APPROVED;
    }
}
