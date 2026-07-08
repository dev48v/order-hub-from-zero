package dev.dev48v.orderhub.inventory;

// Day 18 — the domain-level failure raised when order-service could not reserve stock in inventory-service.
// WHY translate here: the order flow shouldn't leak a transport-specific feign.FeignException up to the
// web layer. Instead we catch the Feign failure (a 404 unknown SKU, a 409 insufficient stock, a 5xx, or a
// connection refused) and rethrow this single, intention-revealing exception. ApiExceptionHandler maps it
// to a clean 409 Conflict — the order can't be honoured because the requested stock couldn't be secured.
//
// This is the sharp edge of a SYNCHRONOUS call: order-service is now COUPLED to inventory-service being
// up and answering. If the dependency says no, or can't be reached, placing the order fails. (Days 14–15
// showed how to soften that with retries/circuit-breakers/fallbacks; Phase 4 will decouple it entirely by
// making the exchange event-driven. Today we simply surface the coupling honestly.)
public class InventoryReservationException extends RuntimeException {

    private final String sku;
    private final int quantity;

    public InventoryReservationException(String sku, int quantity, Throwable cause) {
        super("Could not reserve " + quantity + " unit(s) of '" + sku + "' in inventory-service", cause);
        this.sku = sku;
        this.quantity = quantity;
    }

    public String sku() {
        return sku;
    }

    public int quantity() {
        return quantity;
    }
}
