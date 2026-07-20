package dev.dev48v.inventory.events;

import java.time.Instant;

// Day 28 — inventory-service's CONSUMER-SIDE view of the COMPENSATING fact the order saga emits. When a
// downstream step fails (payment declined, or stock could not be reserved), order-service cancels its order
// and publishes OrderCancelled to the order-cancelled topic. inventory-service SUBSCRIBES to it and RELEASES
// (un-reserves) the stock it had held for that order — the compensation for its earlier reserve. That is how
// a distributed saga "rolls back" a step with no shared transaction: not by undoing a commit, but by
// reacting to a compensating event with a compensating operation.
//
// WHY a matching record rather than a shared class: same rule as every event in the system — separate
// bounded contexts coupled only by the JSON CONTRACT. The field names mirror order-service's emitted
// OrderCancelledEvent EXACTLY so Jackson binds the incoming JSON straight onto this record; the producer
// stamps NO Java type headers, so the deserializer is told this concrete target type in KafkaConsumerConfig.
public record OrderCancelledEvent(
        String eventId,     // unique id of THIS OrderCancelled emission
        String orderId,     // which order was cancelled — inventory keys its release off this
        String sku,         // the SKU whose reservation should be released (informational; we release what we hold)
        int quantity,       // how many units were reserved (informational)
        String reason,      // PAYMENT_DECLINED | STOCK_UNAVAILABLE
        Instant occurredAt  // when the saga compensated
) {
}
