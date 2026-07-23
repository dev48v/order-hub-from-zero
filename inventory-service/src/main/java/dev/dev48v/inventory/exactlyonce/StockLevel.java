package dev.dev48v.inventory.exactlyonce;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// Day 32 — the PERSISTENT stock position (maps onto the stock_levels table from V1). This is the durable,
// transactional twin of the in-memory domain.StockItem: same invariant ("never reserve more than is
// available"), but its state lives in the database so the reservation can commit atomically alongside the
// processed_events dedup marker. Keeping the rule inside the entity (reserve() guards it) means the only way
// availability drops is a checked reservation — the web/consumer layers cannot bypass it.
@Entity
@Table(name = "stock_levels")
public class StockLevel {

    @Id
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private int available;

    // JPA requires a no-arg constructor to instantiate rows on load.
    protected StockLevel() {
    }

    public StockLevel(String sku, String name, int available) {
        this.sku = sku;
        this.name = name;
        this.available = available;
    }

    // True when `quantity` units can be reserved without over-committing. The processor checks this to decide
    // between a RESERVED outcome and a graceful INSUFFICIENT_STOCK one.
    public boolean canReserve(int quantity) {
        return quantity > 0 && quantity <= available;
    }

    // Reserve units. The invariant lives here: refuse to over-commit. The processor only calls this after
    // canReserve() returns true, but the guard is kept so the rule is enforced no matter who calls it.
    public void reserve(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("reserve quantity must be positive");
        }
        if (quantity > available) {
            throw new IllegalStateException("cannot reserve " + quantity + " of " + sku
                    + " - only " + available + " available");
        }
        available -= quantity;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public int getAvailable() {
        return available;
    }
}
