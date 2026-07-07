package dev.dev48v.orderhub.inventory;

// Day 14 — the result of an inventory availability check for one item.
// WHY a small record instead of a bare boolean: it also carries HOW the answer was produced, so the
// caller (and the API response) can tell the difference between a real, authoritative "in stock" from
// the downstream service and a DEGRADED best-effort answer the fallback returned while the circuit
// breaker was OPEN. `degraded` is the whole point of the day: when the downstream is unhealthy we
// don't fail the order — we serve a graceful, clearly-labelled reduced-service answer instead.
public record InventoryStatus(
        String item,
        boolean available,   // whether we're treating the item as in stock
        boolean degraded,    // true when this came from the fallback (breaker OPEN or a failed call)
        String source        // human-readable origin: "inventory-service" or "fallback"
) {
    // A live answer from the real downstream service.
    static InventoryStatus live(String item, boolean available) {
        return new InventoryStatus(item, available, false, "inventory-service");
    }

    // The graceful degraded answer the fallback returns when the downstream can't be trusted.
    // We optimistically assume the item IS available so an order can still be PLACED and reconciled
    // later — the safe default for a fulfilment flow is to accept the order, not to reject good
    // customers because a dependency is briefly down. `degraded` flags it so the UI can say so.
    static InventoryStatus degraded(String item) {
        return new InventoryStatus(item, true, true, "fallback");
    }
}
