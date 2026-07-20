package dev.dev48v.inventory.orchestration;

import java.math.BigDecimal;
import java.time.Instant;

// Day 29 — inventory-service's PRODUCER-SIDE reply to a command the orchestrator sent. After handling a
// RESERVE_STOCK (or RELEASE_STOCK) command it publishes this on the saga-replies topic; the orchestrator
// correlates it back by commandId and advances its state machine. Field names mirror order-service's SagaReply
// EXACTLY (shared JSON contract, no type headers).
public record SagaReply(
        String replyId,
        String commandId,     // the SagaCommand.commandId this answers — the correlation token
        String orderId,
        String type,          // STOCK_RESERVED | STOCK_REJECTED | STOCK_RELEASED
        boolean success,
        String reason,
        BigDecimal amount,
        String detail,        // remaining units after a successful reserve
        Instant occurredAt
) {
    public static final String STOCK_RESERVED = "STOCK_RESERVED";
    public static final String STOCK_REJECTED = "STOCK_REJECTED";
    public static final String STOCK_RELEASED = "STOCK_RELEASED";

    public static SagaReply reserved(SagaCommand cmd, int remaining) {
        return new SagaReply(java.util.UUID.randomUUID().toString(), cmd.commandId(), cmd.orderId(),
                STOCK_RESERVED, true, "RESERVED", null, String.valueOf(remaining), Instant.now());
    }

    public static SagaReply rejected(SagaCommand cmd, String reason) {
        return new SagaReply(java.util.UUID.randomUUID().toString(), cmd.commandId(), cmd.orderId(),
                STOCK_REJECTED, false, reason, null, null, Instant.now());
    }

    public static SagaReply released(SagaCommand cmd) {
        return new SagaReply(java.util.UUID.randomUUID().toString(), cmd.commandId(), cmd.orderId(),
                STOCK_RELEASED, true, null, null, null, Instant.now());
    }
}
