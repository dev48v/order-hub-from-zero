package dev.dev48v.shipping.events;

import java.math.BigDecimal;
import java.time.Instant;

// Day 28 — shipping-service's OWN consumer-side view of the PaymentProcessed event that payment-service
// (Day 27) publishes to the payment-events topic. Same discipline as inventory-service's and payment-service's
// copies of OrderPlaced: the services are separate bounded contexts and separate deployables, so they do NOT
// share a compiled type — sharing one would couple their build and release cycles, the very thing event-driven
// decoupling avoids. They share a CONTRACT: the JSON shape on the topic. This record mirrors payment-service's
// emitted field names EXACTLY (eventId, orderId, customer, amount, status, reason, causedByEventId, occurredAt)
// so Jackson binds the incoming JSON straight onto it; a field the producer adds later is harmlessly ignored
// (forward-compatible). payment-service sends NO Java type headers (ADD_TYPE_INFO_HEADERS=false), so the
// deserializer is told this concrete target type in KafkaConsumerConfig.
//
// This is the TRIGGER of the choreography's final hop: shipping-service reads the payment DECISION here and,
// with no further calls, decides the order's fate — ship it (approved) or compensate it (declined).
public record PaymentProcessedEvent(
        String eventId,          // unique id of the PaymentProcessed emission — carried on as causedByEventId
        String orderId,          // which order was charged — the idempotency key for the saga
        String customer,         // who was charged
        BigDecimal amount,       // how much was charged
        String status,           // APPROVED | DECLINED — the fork in the saga
        String reason,           // APPROVED | AMOUNT_OVER_LIMIT | TEST_CARD_DECLINED — carried into a cancellation
        String causedByEventId,  // the OrderPlaced eventId that ultimately triggered this — end-to-end tracing
        Instant occurredAt       // when payment-service produced this result
) {

    // The APPROVED / DECLINED fork, in one place so the listener reads cleanly.
    public boolean isApproved() {
        return "APPROVED".equals(status);
    }
}
