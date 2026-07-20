package dev.dev48v.orderhub.saga;

import java.time.Instant;

// Day 28 — order-service's CONSUMER-SIDE view of the result inventory-service now publishes. In the
// choreography saga, inventory-service reacts to OrderPlaced by reserving stock and then ANSWERS with a
// StockReserved fact on the "inventory-events" topic (RESERVED, or a failure outcome). The order saga
// subscribes to that fact — it is one of the two signals (the other is PaymentProcessed) it waits on
// before it can decide whether to ship or compensate.
//
// WHY a matching record rather than a SHARED class: order-service and inventory-service are separate
// bounded contexts and separate deployables — sharing a compiled type would couple their build and
// release cycles, the very thing event-driven decoupling avoids. They share a CONTRACT: the JSON shape.
// The field names mirror inventory-service's emitted StockReservedEvent EXACTLY so Jackson binds the
// incoming JSON straight onto this record; the producer sends NO Java type headers, so the deserializer
// is told this concrete target type in SagaKafkaConfig.
public record StockReservedEvent(
        String eventId,          // unique id of THIS StockReserved emission
        String orderId,          // which order the reservation is about — the saga key
        String sku,              // the reserved SKU
        int quantity,            // how many units were requested
        String outcome,          // RESERVED | INSUFFICIENT_STOCK | UNKNOWN_SKU
        int remaining,           // units left after a successful reserve; -1 when nothing was reserved
        String causedByEventId,  // the OrderPlaced eventId that triggered the reservation — for tracing
        Instant occurredAt       // when inventory-service produced this result
) {

    public boolean isReserved() {
        return "RESERVED".equals(outcome);
    }
}
