package dev.dev48v.shipping.orchestration;

import java.math.BigDecimal;
import java.time.Instant;

// Day 29 — shipping-service's CONSUMER-SIDE view of the orchestrator's command. In the ORCHESTRATION saga the
// coordinator sends explicit commands on saga-commands; shipping-service subscribes and handles the ONE kind it
// owns — SCHEDULE_SHIPMENT — ignoring the rest. Field names mirror order-service's SagaCommand EXACTLY (shared
// JSON contract, no type headers).
public record SagaCommand(
        String commandId,
        String orderId,
        String type,          // SCHEDULE_SHIPMENT (this service) | others (ignored)
        String sku,
        int quantity,
        String customer,
        BigDecimal amount,    // the approved charge, carried through onto the shipment
        Instant occurredAt
) {
    public static final String SCHEDULE_SHIPMENT = "SCHEDULE_SHIPMENT";
}
