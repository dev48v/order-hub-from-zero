package dev.dev48v.orderhub.orchestration;

import java.math.BigDecimal;
import java.time.Instant;

// Day 29 — the per-order SAGA STATE the orchestrator tracks. It is the direct analogue of Day 28's SagaState,
// but where the choreography version only had to CORRELATE two independent facts, this version drives an
// explicit STATE MACHINE (OrchestrationStep): it remembers exactly which command is outstanding, so the
// orchestrator knows what the next reply should be and what to do when it lands. It also carries the order's
// data (customer, item, quantity, amount) so the orchestrator can build the next command without re-fetching,
// and a `stockReserved` flag so it knows whether a compensation must RELEASE stock (only if it was reserved).
//
// Like SagaState it is deliberately mutable and NOT thread-safe on its own — SagaOrchestrator guards every
// read-modify with `synchronized (state)`, so all the ordering/atomicity lives in the coordinator and this
// stays a plain data holder. The current `step` doubles as the idempotency guard: once it reaches a terminal
// state (COMPLETED / CANCELLED) the orchestrator ignores every further reply, so a Kafka redelivery (at-least-
// once) can never advance the flow twice.
class OrchestrationState {

    private final String orderId;
    private final String customer;
    private final String item;
    private final int quantity;
    private final Instant startedAt = Instant.now();

    private OrchestrationStep step = OrchestrationStep.STARTED;
    private boolean stockReserved;   // true once inventory replied STOCK_RESERVED — gates compensation release
    private BigDecimal amount;       // the charged amount, learned from PAYMENT_APPROVED
    private String reason;           // why cancelled (PAYMENT_DECLINED | STOCK_UNAVAILABLE), else null

    OrchestrationState(String orderId, String customer, String item, int quantity) {
        this.orderId = orderId;
        this.customer = customer;
        this.item = item;
        this.quantity = quantity;
    }

    String orderId() { return orderId; }
    String customer() { return customer; }
    String item() { return item; }
    int quantity() { return quantity; }
    Instant startedAt() { return startedAt; }

    OrchestrationStep step() { return step; }
    boolean isTerminal() { return step.isTerminal(); }
    boolean stockReserved() { return stockReserved; }
    BigDecimal amount() { return amount; }
    String reason() { return reason; }

    // ---- the explicit transitions the orchestrator drives (all under synchronized(state)) ----
    void awaitStock()    { this.step = OrchestrationStep.AWAITING_STOCK; }
    void awaitPayment()  { this.step = OrchestrationStep.AWAITING_PAYMENT; }
    void awaitShipment() { this.step = OrchestrationStep.AWAITING_SHIPMENT; }

    void markStockReserved() { this.stockReserved = true; }
    void recordAmount(BigDecimal amount) { this.amount = amount; }

    void complete() { this.step = OrchestrationStep.COMPLETED; }
    void cancel(String reason) {
        this.step = OrchestrationStep.CANCELLED;
        this.reason = reason;
    }

    // The terminal result label for the read surface: COMPLETED | CANCELLED | null (still running).
    String result() {
        return switch (step) {
            case COMPLETED -> "COMPLETED";
            case CANCELLED -> "CANCELLED";
            default -> null;
        };
    }
}
