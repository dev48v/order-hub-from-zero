package dev.dev48v.notification.events;

import java.time.Instant;

// Day 41 — notification-service's OWN consumer-side view of the OrderPlaced event that order-service (Day 25)
// publishes to the "order-placed" topic. Same discipline as inventory-service's and payment-service's copies:
// the services are separate bounded contexts and separate deployables, so they do NOT share a compiled type —
// sharing one would couple their build and release cycles, the very thing event-driven decoupling avoids.
// They share a CONTRACT: the JSON shape on the topic. This record mirrors the producer's field names EXACTLY
// (eventId, orderId, customer, item, quantity, status, placedAt, occurredAt) so Jackson binds the incoming
// JSON straight onto it; a field the producer adds later is harmlessly ignored (forward-compatible). The
// producer sends NO Java type headers (ADD_TYPE_INFO_HEADERS=false), so the deserializer is told this concrete
// target type in KafkaConsumerConfig.
//
// notification-service needs `customer` (who to notify), `orderId` (what the message is about), `item`/
// `quantity` (to enrich the "order placed" copy) and `eventId` (the idempotency key — so a redelivery of the
// SAME emission never notifies twice).
public record OrderPlacedEvent(
        String eventId,      // unique id of the OrderPlaced emission — the idempotency key for de-duping notifications
        String orderId,      // which order this is about — shown in the message ("your order #X was placed")
        String customer,     // who placed it — the notification recipient
        String item,         // the item ordered — enriches the message copy
        int quantity,        // how many units — enriches the message copy
        String status,       // the order's status at emit time (PLACED)
        Instant placedAt,    // when the order was created
        Instant occurredAt   // when the event was produced
) {
}
