package dev.dev48v.orderhub.orchestration;

// Day 29 — the ORCHESTRATION saga's explicit STATE MACHINE, written down as an enum. This is the concrete
// payoff of orchestration over choreography: the WHOLE order flow lives in ONE place as a sequence of named
// states, instead of being emergent from who-reacts-to-what across seven services. The orchestrator advances
// a saga through these states as replies arrive, and every transition is a deliberate, readable step.
//
//   STARTED           → the saga exists (OrderPlaced seen) but no command has gone out yet
//   AWAITING_STOCK    → RESERVE_STOCK sent; waiting for STOCK_RESERVED / STOCK_REJECTED
//   AWAITING_PAYMENT  → PROCESS_PAYMENT sent; waiting for PAYMENT_APPROVED / PAYMENT_DECLINED
//   AWAITING_SHIPMENT → SCHEDULE_SHIPMENT sent; waiting for SHIPMENT_SCHEDULED
//   COMPLETED         → terminal, happy path: order shipped
//   CANCELLED         → terminal, compensated: order cancelled (stock released if it had been reserved)
//
// COMPLETED and CANCELLED are the two TERMINAL states. Which awaiting-state the saga is in is also the
// idempotency guard: a redelivered reply for a step we have already advanced past no longer matches the
// current state, so it is ignored — the saga acts on each reply exactly once.
public enum OrchestrationStep {
    STARTED,
    AWAITING_STOCK,
    AWAITING_PAYMENT,
    AWAITING_SHIPMENT,
    COMPLETED,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED;
    }
}
