package dev.dev48v.inventory.stock;

// Day 17 — a reservation asked for more units than are available. Not a server fault and not
// retryable as-is, so the web layer maps it to 409 Conflict. It carries the numbers so the API can
// tell the caller exactly what went wrong (requested vs available).
public class InsufficientStockException extends RuntimeException {

    private final String sku;
    private final int requested;
    private final int available;

    public InsufficientStockException(String sku, int requested, int available) {
        super("cannot reserve " + requested + " of '" + sku + "' — only " + available + " available");
        this.sku = sku;
        this.requested = requested;
        this.available = available;
    }

    public String sku() {
        return sku;
    }

    public int requested() {
        return requested;
    }

    public int available() {
        return available;
    }
}
