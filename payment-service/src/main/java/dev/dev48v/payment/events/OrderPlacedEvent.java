package dev.dev48v.payment.events;

import java.time.Instant;

// Day 27 — payment-service's OWN consumer-side view of the OrderPlaced event, identical in spirit to
// inventory-service's copy. order-service (Day 25) publishes an OrderPlaced to the "order-placed" topic as
// JSON; BOTH inventory-service and payment-service consume it — each in its own consumer group, each with
// its own matching record.
//
// WHY a matching record rather than a SHARED class: the services are separate bounded contexts and separate
// deployables — sharing a compiled type would couple their build and release cycles, which is exactly what
// event-driven decoupling avoids. They share a CONTRACT: the JSON shape. This record mirrors the producer's
// field names EXACTLY (eventId, orderId, customer, item, quantity, status, placedAt, occurredAt) so Jackson
// binds the incoming JSON straight onto it; a field the producer adds later is harmlessly ignored
// (forward-compatible). The producer sends NO Java type headers (ADD_TYPE_INFO_HEADERS=false), so the
// deserializer is told this concrete target type in KafkaConsumerConfig.
//
// payment-service needs `customer` (the test-card check) and `quantity` (to derive the charge amount), plus
// `orderId` to key idempotency and `eventId` to trace which OrderPlaced caused which PaymentProcessed.
public record OrderPlacedEvent(
        String eventId,      // unique id of the OrderPlaced emission — carried into PaymentProcessed for tracing
        String orderId,      // which order this is about — the idempotency key here (don't double-charge)
        String customer,     // who placed it — a "test card" customer forces a decline
        String item,         // the item ordered
        int quantity,        // how many units — turned into a charge amount via the configured unit price
        String status,       // the order's status at emit time (PLACED)
        Instant placedAt,    // when the order was created
        Instant occurredAt   // when the event was produced
) {
}
