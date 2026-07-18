package dev.dev48v.payment.events;

import dev.dev48v.payment.payment.Payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// Day 27 — the RESULT event payment-service publishes to the payment-events topic after deciding an order's
// payment. This is the payoff of a purely event-driven service: it consumed a fact (OrderPlaced) and it
// answers with a NEW fact (PaymentProcessed) rather than returning a value to a caller. Whoever cares about
// the outcome — Day 28's choreography saga, a notification service, the order's status projection — subscribes
// to payment-events; payment-service neither knows nor cares who reacts.
//
// It carries the whole outcome so a downstream consumer can act WITHOUT calling back: the order id, the
// customer, the amount charged, the decision (APPROVED / DECLINED) and a machine-readable reason. `eventId`
// is this event's own unique id; `causedByEventId` is the OrderPlaced eventId that triggered it, so the two
// facts can be correlated end to end. Serialized to JSON with NO type headers (language-neutral), exactly like
// order-service's producer.
public record PaymentProcessedEvent(
        String eventId,          // unique id of THIS PaymentProcessed emission
        String orderId,          // which order was charged
        String customer,         // who was charged
        BigDecimal amount,       // how much was charged
        String status,           // APPROVED | DECLINED
        String reason,           // APPROVED | AMOUNT_OVER_LIMIT | TEST_CARD_DECLINED
        String causedByEventId,  // the OrderPlaced eventId that triggered this — for end-to-end tracing
        Instant occurredAt       // when payment-service produced this result
) {

    // Build the result event from a decided Payment plus the id of the OrderPlaced that caused it.
    public static PaymentProcessedEvent from(Payment payment, String causedByEventId) {
        return new PaymentProcessedEvent(
                UUID.randomUUID().toString(),
                payment.getOrderId(),
                payment.getCustomer(),
                payment.getAmount(),
                payment.getStatus().name(),
                payment.getReason(),
                causedByEventId,
                Instant.now());
    }

    public boolean isApproved() {
        return "APPROVED".equals(status);
    }
}
