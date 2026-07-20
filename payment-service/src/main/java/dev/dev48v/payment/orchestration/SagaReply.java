package dev.dev48v.payment.orchestration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// Day 29 — payment-service's PRODUCER-SIDE reply to a PROCESS_PAYMENT command. After deciding the charge it
// publishes this on saga-replies; the orchestrator correlates it by commandId and either advances to the
// shipment step (APPROVED, carrying the amount forward) or compensates (DECLINED). Field names mirror
// order-service's SagaReply EXACTLY (shared JSON contract, no type headers).
public record SagaReply(
        String replyId,
        String commandId,     // the SagaCommand.commandId this answers — the correlation token
        String orderId,
        String type,          // PAYMENT_APPROVED | PAYMENT_DECLINED
        boolean success,
        String reason,
        BigDecimal amount,    // the charged amount, carried forward to the shipment on approval
        String detail,
        Instant occurredAt
) {
    public static final String PAYMENT_APPROVED = "PAYMENT_APPROVED";
    public static final String PAYMENT_DECLINED = "PAYMENT_DECLINED";

    public static SagaReply approved(SagaCommand cmd, BigDecimal amount) {
        return new SagaReply(UUID.randomUUID().toString(), cmd.commandId(), cmd.orderId(),
                PAYMENT_APPROVED, true, "APPROVED", amount, null, Instant.now());
    }

    public static SagaReply declined(SagaCommand cmd, String reason, BigDecimal amount) {
        return new SagaReply(UUID.randomUUID().toString(), cmd.commandId(), cmd.orderId(),
                PAYMENT_DECLINED, false, reason, amount, null, Instant.now());
    }
}
