package dev.dev48v.inventory.stock;

// Day 17 — asked about a SKU the inventory service doesn't stock. A caller error, mapped to
// 404 Not Found by the web layer. Its own type so the exception handler can name it precisely.
public class UnknownSkuException extends RuntimeException {
    public UnknownSkuException(String sku) {
        super("no stock record for sku '" + sku + "'");
    }
}
