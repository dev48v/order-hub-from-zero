package dev.dev48v.orderhub.orchestration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// Day 29 — the ORCHESTRATION saga's REPLY: a participant's answer to a command the orchestrator sent. Where a
// command is an imperative ("reserve stock"), a reply reports the OUTCOME of executing it. Every participant
// sends its reply back on ONE topic (saga-replies) as this single envelope, discriminated by `type`; the
// orchestrator is the only consumer of that topic and drives the saga's state machine off each reply. This is
// the "await a reply" half of command/reply — the flow does NOT advance until the awaited reply lands, which is
// exactly how the orchestrator keeps the whole order flow in ONE explicit state machine instead of scattering
// it across services (the choreography drawback).
//
// commandId echoes the SagaCommand that produced this reply — the correlation token. success + reason make the
// outcome machine-readable so the orchestrator can branch (approve → forward, decline → compensate). amount is
// the charged amount a payment reply carries forward to the shipment; detail carries a human-friendly extra
// (remaining stock, the shipment tracking number). Serialized to JSON with NO Java type headers.
public record SagaReply(
        String replyId,       // unique id of THIS reply emission
        String commandId,     // the SagaCommand.commandId this answers — the correlation token
        String orderId,       // which order — the saga key + Kafka message key
        String type,          // STOCK_RESERVED | STOCK_REJECTED | PAYMENT_APPROVED | PAYMENT_DECLINED | SHIPMENT_SCHEDULED | STOCK_RELEASED
        boolean success,      // did the step succeed? (redundant with type, kept for a quick branch/log)
        String reason,        // why it failed (INSUFFICIENT_STOCK, AMOUNT_OVER_LIMIT, …), else null/APPROVED
        BigDecimal amount,    // the charged amount (payment replies) carried forward to the shipment; else null
        String detail,        // extra: remaining stock after reserve, or the shipment tracking number; else null
        Instant occurredAt    // when the participant produced this reply
) {

    // ---- reply kinds (the outcome a participant reports) ----
    public static final String STOCK_RESERVED = "STOCK_RESERVED";        // inventory reserved OK
    public static final String STOCK_REJECTED = "STOCK_REJECTED";        // inventory could not reserve
    public static final String PAYMENT_APPROVED = "PAYMENT_APPROVED";    // payment approved (carries amount)
    public static final String PAYMENT_DECLINED = "PAYMENT_DECLINED";    // payment declined
    public static final String SHIPMENT_SCHEDULED = "SHIPMENT_SCHEDULED";// shipping scheduled (carries tracking)
    public static final String STOCK_RELEASED = "STOCK_RELEASED";        // compensation ack: units put back

    // Reply builders. These are what a PARTICIPANT emits (inventory/payment/shipping build the same shapes from
    // their own copy of this contract); they live here too so the orchestrator's @EmbeddedKafka test can play
    // the participant roles using this record, and so the field layout of each reply kind is documented once.
    public static SagaReply reserved(SagaCommand cmd, int remaining) {
        return new SagaReply(UUID.randomUUID().toString(), cmd.commandId(), cmd.orderId(),
                STOCK_RESERVED, true, "RESERVED", null, String.valueOf(remaining), Instant.now());
    }

    public static SagaReply rejected(SagaCommand cmd, String reason) {
        return new SagaReply(UUID.randomUUID().toString(), cmd.commandId(), cmd.orderId(),
                STOCK_REJECTED, false, reason, null, null, Instant.now());
    }

    public static SagaReply approved(SagaCommand cmd, BigDecimal amount) {
        return new SagaReply(UUID.randomUUID().toString(), cmd.commandId(), cmd.orderId(),
                PAYMENT_APPROVED, true, "APPROVED", amount, null, Instant.now());
    }

    public static SagaReply declined(SagaCommand cmd, String reason, BigDecimal amount) {
        return new SagaReply(UUID.randomUUID().toString(), cmd.commandId(), cmd.orderId(),
                PAYMENT_DECLINED, false, reason, amount, null, Instant.now());
    }

    public static SagaReply scheduled(SagaCommand cmd, String trackingNumber) {
        return new SagaReply(UUID.randomUUID().toString(), cmd.commandId(), cmd.orderId(),
                SHIPMENT_SCHEDULED, true, null, cmd.amount(), trackingNumber, Instant.now());
    }

    public static SagaReply released(SagaCommand cmd) {
        return new SagaReply(UUID.randomUUID().toString(), cmd.commandId(), cmd.orderId(),
                STOCK_RELEASED, true, null, null, null, Instant.now());
    }
}
