package dev.dev48v.inventory.events;

import java.time.Instant;

// Day 26 — the CONSUMER-SIDE view of the OrderPlaced event. order-service (Day 25) publishes an
// OrderPlaced to the "order-placed" topic as JSON; inventory-service consumes it here to reserve stock.
//
// WHY a matching record rather than a SHARED class: the two services are separate bounded contexts and
// separate deployables — sharing a compiled type would couple their build and release cycles, which is
// exactly what event-driven decoupling is meant to avoid. Instead they share a CONTRACT: the JSON shape.
// This record mirrors the producer's field names EXACTLY (eventId, orderId, customer, item, quantity,
// status, placedAt, occurredAt) so Jackson binds the incoming JSON straight onto it. If the producer ever
// adds a field, this consumer simply ignores it (forward-compatible); the fields it needs must keep their
// names. The producer sends NO Java type headers (ADD_TYPE_INFO_HEADERS=false), so the deserializer is
// told this concrete target type in KafkaConsumerConfig — that is the whole reason the shapes must agree.
//
// This service only really needs `item` (the SKU) and `quantity` to reserve stock; `orderId` keys the
// idempotency check, and `eventId` is the unique emission id (Day 32 uses it for exactly-once). The rest
// travel with the fact and are kept so a consumer can act WITHOUT calling back to order-service.
public record OrderPlacedEvent(
        String eventId,      // unique id of this event emission (idempotency, Day 32)
        String orderId,      // which order this is about — the idempotency key here
        String customer,     // who placed it
        String item,         // the item ordered == the inventory SKU to reserve against
        int quantity,        // how many units to reserve
        String status,       // the order's status at emit time (PLACED)
        Instant placedAt,    // when the order was created
        Instant occurredAt   // when the event was produced
) {
}
