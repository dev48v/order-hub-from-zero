package dev.dev48v.inventory.events;

import java.time.Instant;

// Day 26 — a small, immutable RECORD of what the consumer did with one OrderPlaced event. It is the
// consumer's audit trail and the "mark" that makes graceful failure observable: whether an order's stock
// was reserved, or couldn't be (insufficient stock / unknown SKU). Keyed by orderId in the ReservationLedger.
//
// WHY record the FAILURES too, instead of just dropping them: a consumer must NOT crash the listener on a
// business failure (that would re-deliver the record forever). So it swallows the domain exception and
// leaves a durable note here instead. In a fuller system this note is what a saga (Day 28) inspects to
// compensate — e.g. cancel the order when stock can't be reserved. Today it's an in-memory ledger; the
// point is the outcome is captured, not lost.
public record Reservation(
        String orderId,
        String eventId,
        String sku,
        int quantity,
        String outcome,   // RESERVED | INSUFFICIENT_STOCK | UNKNOWN_SKU
        int remaining,    // units left after a successful reserve; -1 when nothing was reserved
        Instant reservedAt
) {

    public static Reservation reserved(String orderId, String eventId, String sku, int quantity, int remaining) {
        return new Reservation(orderId, eventId, sku, quantity, "RESERVED", remaining, Instant.now());
    }

    public static Reservation failed(String orderId, String eventId, String sku, int quantity, String outcome) {
        return new Reservation(orderId, eventId, sku, quantity, outcome, -1, Instant.now());
    }

    public boolean isReserved() {
        return "RESERVED".equals(outcome);
    }
}
