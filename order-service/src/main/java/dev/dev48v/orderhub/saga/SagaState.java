package dev.dev48v.orderhub.saga;

import java.math.BigDecimal;
import java.time.Instant;

// Day 28 — the per-order SAGA STATE: the small amount of memory the choreography needs to correlate the two
// independent result events (StockReserved from inventory, PaymentProcessed from payment) that arrive on
// different topics, in any order, on different threads. A saga is a long-running transaction split across
// services; SOMETHING has to remember "I've seen the stock result but not the payment result yet". That
// something is this object, keyed by order id in OrderSaga's map.
//
// It is deliberately mutable and NOT thread-safe on its own — OrderSaga guards every read-modify on it with
// `synchronized (state)`, so all the ordering/atomicity lives in one place (the manager) and this stays a
// plain data holder. The `terminal` flag is the idempotency guard: once the saga has decided (shipped or
// cancelled), it is latched, so a redelivered event (Kafka is at-least-once) re-runs evaluate() but the
// decision — and the event it emits — happens exactly once.
class SagaState {

    private final String orderId;
    private final Instant startedAt = Instant.now();

    // The two awaited signals. null = not seen yet.
    private String stockOutcome;    // RESERVED | INSUFFICIENT_STOCK | UNKNOWN_SKU
    private String paymentOutcome;  // APPROVED | DECLINED
    private BigDecimal amount;      // the charged amount, learned from PaymentProcessed

    // The latched terminal decision. terminal=true means the saga has already acted exactly once.
    private boolean terminal;
    private String result;          // SHIPPED | CANCELLED  (null until terminal)
    private String reason;          // why cancelled (PAYMENT_DECLINED | STOCK_UNAVAILABLE), else null

    SagaState(String orderId) {
        this.orderId = orderId;
    }

    String orderId() { return orderId; }
    Instant startedAt() { return startedAt; }

    String stockOutcome() { return stockOutcome; }
    void recordStock(String outcome) { this.stockOutcome = outcome; }
    boolean stockReserved() { return "RESERVED".equals(stockOutcome); }
    boolean stockFailed() { return stockOutcome != null && !"RESERVED".equals(stockOutcome); }

    String paymentOutcome() { return paymentOutcome; }
    void recordPayment(String outcome, BigDecimal amount) {
        this.paymentOutcome = outcome;
        this.amount = amount;
    }
    boolean paymentApproved() { return "APPROVED".equals(paymentOutcome); }
    boolean paymentDeclined() { return "DECLINED".equals(paymentOutcome); }

    BigDecimal amount() { return amount; }

    boolean isTerminal() { return terminal; }
    String result() { return result; }
    String reason() { return reason; }

    // Latch the terminal decision. Called once, from inside OrderSaga's synchronized block.
    void markShipped() {
        this.terminal = true;
        this.result = "SHIPPED";
    }

    void markCancelled(String reason) {
        this.terminal = true;
        this.result = "CANCELLED";
        this.reason = reason;
    }
}
