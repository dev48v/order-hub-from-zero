package dev.dev48v.orderhub.orchestration;

import java.math.BigDecimal;
import java.time.Instant;

// Day 29 — the ORCHESTRATION saga's COMMAND, and the single most important contrast with Day 28. A
// choreography saga has no commands at all: services publish FACTS ("StockReserved") and other services
// react to them. An ORCHESTRATION saga is the opposite — a central coordinator (SagaOrchestrator) sends
// explicit COMMANDS ("reserve this stock") and awaits explicit REPLIES. A command is an IMPERATIVE addressed
// to a specific participant: "do this". That is why it is named in the imperative present tense (RESERVE_STOCK,
// PROCESS_PAYMENT) — a fact is past tense ("StockReserved"), a command is a request for future work.
//
// All four command kinds travel on ONE topic (saga-commands) as this single envelope, discriminated by `type`.
// Each participant service subscribes to that topic in its OWN consumer group, so every service gets a copy of
// every command and simply IGNORES the kinds it does not own (inventory handles RESERVE_STOCK + RELEASE_STOCK;
// payment handles PROCESS_PAYMENT; shipping handles SCHEDULE_SHIPMENT). commandId is a fresh unique id per
// emission — the correlation token a reply echoes back so the orchestrator can match reply→command. Serialized
// to JSON with NO Java type headers (language-neutral), exactly like every event in the system.
public record SagaCommand(
        String commandId,     // unique id of THIS command emission — echoed back as the reply's commandId
        String orderId,       // which order this command is about — the saga key + Kafka message key
        String type,          // RESERVE_STOCK | PROCESS_PAYMENT | SCHEDULE_SHIPMENT | RELEASE_STOCK
        String sku,           // the item / SKU the order is for
        int quantity,         // how many units
        String customer,      // who placed the order (payment needs it; carried through for the shipment)
        BigDecimal amount,    // the charged amount — null until known; set on SCHEDULE_SHIPMENT (approved amount)
        Instant occurredAt    // when the orchestrator issued this command
) {

    // ---- command kinds (the imperative the orchestrator issues) ----
    public static final String RESERVE_STOCK = "RESERVE_STOCK";        // forward: inventory reserves the units
    public static final String PROCESS_PAYMENT = "PROCESS_PAYMENT";    // forward: payment decides the charge
    public static final String SCHEDULE_SHIPMENT = "SCHEDULE_SHIPMENT";// forward: shipping schedules + tracks
    public static final String RELEASE_STOCK = "RELEASE_STOCK";        // COMPENSATION: inventory un-reserves
}
