package dev.dev48v.orderhub.inventory;

// Day 18 — order-service's OWN view of the stock record inventory-service returns over HTTP.
// WHY a separate record here (a near-copy of inventory-service's StockView) instead of a shared class:
// the two services deliberately share NO code — each bounded context owns its own model, and the ONLY
// thing that couples them is the wire CONTRACT (the JSON shape), not a Java type on a common classpath.
// Feign binds the response JSON onto this record by field name, so as long as the field names match the
// contract it maps cleanly; if inventory-service adds a field we don't need, we simply don't declare it.
// This is the consumer's copy of the contract, and it can evolve independently of the producer's.
public record StockView(String sku, String name, int available, boolean inStock) {
}
