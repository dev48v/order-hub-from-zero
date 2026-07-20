package dev.dev48v.inventory.events;

import java.time.Instant;
import java.util.UUID;

// Day 28 — inventory-service's ANSWER event, the piece that makes it a full choreography participant rather
// than a dead-end consumer. Until today inventory-service only CONSUMED OrderPlaced (Day 26): it reserved
// stock and recorded the outcome in its ledger, but told no one. The saga needs to HEAR that outcome — the
// order saga waits on "stock reserved" as one of its two signals before it can ship — so inventory now
// PUBLISHES this StockReserved fact to the inventory-events topic after every reservation attempt.
//
// It carries the OUTCOME, not just a success: RESERVED on the happy path, or INSUFFICIENT_STOCK / UNKNOWN_SKU
// when the reservation couldn't be made. Emitting the failure as a first-class fact is what lets the saga
// COMPENSATE (cancel the order) instead of hanging forever waiting for a reserve that will never come.
//
// WHY a record that MIRRORS order-service's consumer-side StockReservedEvent field-for-field rather than a
// shared class: the two services are separate bounded contexts and separate deployables — they are coupled
// only by the JSON CONTRACT, never a compiled type. The field names here match order-service's saga
// StockReservedEvent EXACTLY so Jackson binds this JSON straight onto that record; the producer stamps NO
// Java type headers (language-neutral), so the consumer is told the concrete target type on its side.
public record StockReservedEvent(
        String eventId,          // unique id of THIS StockReserved emission
        String orderId,          // which order the reservation is about — the saga key
        String sku,              // the SKU we tried to reserve
        int quantity,            // how many units were requested
        String outcome,          // RESERVED | INSUFFICIENT_STOCK | UNKNOWN_SKU
        int remaining,           // units left after a successful reserve; -1 when nothing was reserved
        String causedByEventId,  // the OrderPlaced eventId that triggered the reservation — for tracing
        Instant occurredAt       // when inventory-service produced this result
) {

    // Build the result fact from a recorded Reservation. A brand-new eventId is minted for every emission;
    // the Reservation's own eventId is the OrderPlaced id that CAUSED it, carried through for tracing.
    public static StockReservedEvent from(Reservation reservation) {
        return new StockReservedEvent(
                UUID.randomUUID().toString(),
                reservation.orderId(),
                reservation.sku(),
                reservation.quantity(),
                reservation.outcome(),
                reservation.remaining(),
                reservation.eventId(),
                Instant.now());
    }

    public boolean isReserved() {
        return "RESERVED".equals(outcome);
    }
}
