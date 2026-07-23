package dev.dev48v.inventory.exactlyonce;

// Day 32 — the result of handling one delivery, returned by ExactlyOnceProcessor so the consumer (and the
// tests) can see exactly what happened. Four cases, three of which write a dedup marker:
//
//   RESERVED           — first time seen, stock was available, units reserved. Marker written.
//   INSUFFICIENT_STOCK — first time seen, but not enough on hand. A graceful business outcome (not an error):
//                        marker still written so it is not reprocessed, and stock is untouched.
//   UNKNOWN_SKU        — first time seen, but the SKU is not in the catalogue. Same policy: marker written,
//                        no stock change.
//   DUPLICATE_SKIPPED  — this exact record (topic/partition/offset) was already processed; a redelivery. No
//                        marker written (it already exists) and, crucially, NO business effect applied.
public enum ProcessOutcome {
    RESERVED,
    INSUFFICIENT_STOCK,
    UNKNOWN_SKU,
    DUPLICATE_SKIPPED;

    // True when this delivery actually performed the reservation (i.e. the business effect ran this time).
    public boolean isReserved() {
        return this == RESERVED;
    }

    // True when the delivery was recognised as a redelivery and skipped without any effect.
    public boolean isDuplicate() {
        return this == DUPLICATE_SKIPPED;
    }
}
