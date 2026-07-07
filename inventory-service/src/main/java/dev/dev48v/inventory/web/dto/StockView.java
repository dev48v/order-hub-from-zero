package dev.dev48v.inventory.web.dto;

import dev.dev48v.inventory.domain.StockItem;

// Day 17 — the API response shape for a stock record. A DTO separate from the StockItem domain
// object (same request/response-separation discipline order-service uses): the wire contract can
// evolve independently of the internal model, and we choose exactly which fields to expose. This
// contract is what order-service will bind to over HTTP from Day 18 — so it's a deliberate, stable
// shape, not the domain object leaked by accident.
public record StockView(String sku, String name, int available, boolean inStock) {

    public static StockView from(StockItem item) {
        return new StockView(item.sku(), item.name(), item.available(), item.inStock());
    }
}
