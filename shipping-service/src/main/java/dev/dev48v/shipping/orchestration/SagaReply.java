package dev.dev48v.shipping.orchestration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// Day 29 — shipping-service's PRODUCER-SIDE reply to a SCHEDULE_SHIPMENT command. After scheduling the shipment
// (and minting a tracking number) it publishes this on saga-replies; the orchestrator correlates it by
// commandId and completes the saga. Field names mirror order-service's SagaReply EXACTLY (shared JSON contract,
// no type headers).
public record SagaReply(
        String replyId,
        String commandId,     // the SagaCommand.commandId this answers — the correlation token
        String orderId,
        String type,          // SHIPMENT_SCHEDULED
        boolean success,
        String reason,
        BigDecimal amount,
        String detail,        // the shipment tracking number
        Instant occurredAt
) {
    public static final String SHIPMENT_SCHEDULED = "SHIPMENT_SCHEDULED";

    public static SagaReply scheduled(SagaCommand cmd, String trackingNumber) {
        return new SagaReply(UUID.randomUUID().toString(), cmd.commandId(), cmd.orderId(),
                SHIPMENT_SCHEDULED, true, null, cmd.amount(), trackingNumber, Instant.now());
    }
}
