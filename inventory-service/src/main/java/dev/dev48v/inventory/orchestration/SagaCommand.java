package dev.dev48v.inventory.orchestration;

import java.math.BigDecimal;
import java.time.Instant;

// Day 29 — inventory-service's CONSUMER-SIDE view of the orchestrator's command. In the ORCHESTRATION saga a
// central coordinator (order-service's SagaOrchestrator) sends explicit COMMANDS on the saga-commands topic;
// inventory-service subscribes and handles the two kinds it owns — RESERVE_STOCK (forward) and RELEASE_STOCK
// (the compensation) — ignoring the rest (PROCESS_PAYMENT, SCHEDULE_SHIPMENT).
//
// WHY a matching record rather than a SHARED class: same reasoning as every event in the system — separate
// bounded contexts, separate deployables, coupled only by the JSON CONTRACT, not a compiled type. The field
// names mirror order-service's SagaCommand EXACTLY so Jackson binds the incoming JSON straight onto this record;
// the producer sends NO Java type headers, so the deserializer is told this concrete type in OrchestrationKafkaConfig.
public record SagaCommand(
        String commandId,
        String orderId,
        String type,          // RESERVE_STOCK | PROCESS_PAYMENT | SCHEDULE_SHIPMENT | RELEASE_STOCK
        String sku,
        int quantity,
        String customer,
        BigDecimal amount,
        Instant occurredAt
) {
    public static final String RESERVE_STOCK = "RESERVE_STOCK";
    public static final String RELEASE_STOCK = "RELEASE_STOCK";
}
