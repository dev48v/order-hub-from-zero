package dev.dev48v.payment.orchestration;

import java.math.BigDecimal;
import java.time.Instant;

// Day 29 — payment-service's CONSUMER-SIDE view of the orchestrator's command. In the ORCHESTRATION saga the
// coordinator sends explicit commands on saga-commands; payment-service subscribes and handles the ONE kind it
// owns — PROCESS_PAYMENT — ignoring the rest (RESERVE_STOCK, SCHEDULE_SHIPMENT, RELEASE_STOCK). Field names
// mirror order-service's SagaCommand EXACTLY (shared JSON contract, no type headers).
public record SagaCommand(
        String commandId,
        String orderId,
        String type,          // PROCESS_PAYMENT (this service) | others (ignored)
        String sku,
        int quantity,
        String customer,
        BigDecimal amount,
        Instant occurredAt
) {
    public static final String PROCESS_PAYMENT = "PROCESS_PAYMENT";
}
