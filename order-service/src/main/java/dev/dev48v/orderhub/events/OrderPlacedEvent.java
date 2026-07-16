package dev.dev48v.orderhub.events;

import dev.dev48v.orderhub.domain.Order;

import java.time.Instant;
import java.util.UUID;

// Day 25 — the first DOMAIN EVENT: OrderPlaced.
//
// Phase 4 turns OrderHub event-driven. Until now services called each other SYNCHRONOUSLY (order-service
// -> inventory-service over Feign): the caller blocks until the callee answers, and it must know who to
// call. An EVENT flips that around. When something important happens in the business ("an order was
// placed"), the service that owns that fact publishes a small, immutable record describing it and moves
// on — it does NOT know or care who, if anyone, reacts. Other services SUBSCRIBE to the event and do their
// own work in their own time. This decouples producers from consumers: adding a new reaction (reserve
// stock, take payment, send an email) is a new consumer, with ZERO change to order-service.
//
// An event is a FACT stated in the past tense — "OrderPlaced", not "PlaceOrder" (that would be a command).
// It is immutable (a Java record — all fields final, no setters), self-contained (a consumer can act on it
// without calling back), and carries a timestamp so consumers know WHEN it happened. `eventId` is a unique
// id for THIS emission (not the order id) — Day 32's idempotent consumers use it to detect a redelivery and
// process each event exactly once. `placedAt` is when the order was created; `occurredAt` is when the event
// was emitted (usually the same instant, kept separate because in the outbox pattern of Day 30 they differ).
public record OrderPlacedEvent(
        String eventId,      // unique id of this event emission (for idempotent consumers, Day 32)
        String orderId,      // which order this is about
        String customer,     // who placed it
        String item,         // the item / inventory SKU ordered
        int quantity,        // how many units
        String status,       // the order's status at emit time (PLACED)
        Instant placedAt,    // when the order was created (order.createdAt)
        Instant occurredAt   // when this event was produced
) {

    // Build the event from a freshly-saved order. A dedicated factory keeps the mapping in one place and
    // the call site (the publisher) tiny. A brand-new eventId is minted here for every emission.
    public static OrderPlacedEvent from(Order order) {
        return new OrderPlacedEvent(
                UUID.randomUUID().toString(),
                order.getId(),
                order.getCustomer(),
                order.getItem(),
                order.getQuantity(),
                order.getStatus().name(),
                order.getCreatedAt(),
                Instant.now()
        );
    }
}
