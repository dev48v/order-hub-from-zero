package dev.dev48v.inventory.stock;

import dev.dev48v.inventory.domain.StockItem;

import java.util.List;
import java.util.Optional;

// Day 17 — the repository PORT for stock, mirroring the ports-and-adapters style order-service
// already uses (OrderRepository). The service depends on this interface, not a concrete store, so
// today's in-memory map can be swapped for a JPA/Postgres adapter later with zero service changes.
// A microservice owns its OWN data store — Inventory's persistence is a private implementation
// detail, never shared with Orders.
public interface StockRepository {

    Optional<StockItem> findBySku(String sku);

    List<StockItem> findAll();

    StockItem save(StockItem item);
}
