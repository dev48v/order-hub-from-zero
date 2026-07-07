package dev.dev48v.inventory.domain;

import dev.dev48v.inventory.stock.InsufficientStockException;

// Day 17 — the inventory service's core domain object: the stock position of ONE product (SKU).
// WHY a class with behaviour rather than a bare data record: the rule "you cannot reserve more
// than is available" belongs WITH the data it guards, not scattered across services/controllers —
// the same reason order-service's Order owns confirm(). This keeps the invariant impossible to
// bypass: the only way to reduce availability is reserve(), which enforces the check.
//
// This is the Inventory context's OWN model. It looks nothing like order-service's Order, and that
// is correct: each bounded context models the world in the terms that matter to IT. Inventory cares
// about SKUs and counts; Orders cares about customers and line items. They share no code — when they
// need to exchange information (Day 18) they do it over an API contract, not a shared class.
public class StockItem {

    private final String sku;      // the stock-keeping unit — the stable product identifier
    private final String name;     // human-readable product name
    private int available;         // units on hand that can still be reserved

    public StockItem(String sku, String name, int available) {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (available < 0) {
            throw new IllegalArgumentException("available must not be negative");
        }
        this.sku = sku;
        this.name = name;
        this.available = available;
    }

    // Reserve `quantity` units for an order. The invariant lives here: refuse to over-commit stock.
    // Throwing a domain exception (not returning a boolean) keeps callers honest — a failed
    // reservation can't be silently ignored — and the web layer maps it to a 409 Conflict.
    public void reserve(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("reserve quantity must be positive");
        }
        if (quantity > available) {
            throw new InsufficientStockException(sku, quantity, available);
        }
        available -= quantity;
    }

    // Put units back (a cancelled order, a returned item). Symmetric with reserve().
    public void replenish(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("replenish quantity must be positive");
        }
        available += quantity;
    }

    public String sku() {
        return sku;
    }

    public String name() {
        return name;
    }

    public int available() {
        return available;
    }

    public boolean inStock() {
        return available > 0;
    }
}
