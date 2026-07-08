package dev.dev48v.orderhub.inventory;

// Day 18 — the body order-service sends to inventory-service's POST /api/inventory/{sku}/reserve.
// It mirrors inventory-service's own ReserveRequest field-for-field (just `quantity`): Feign serialises
// this record to the JSON the remote endpoint expects. Like StockView, it's the CONSUMER's copy of the
// wire contract — no shared type, only a shared shape. Inventory-service still re-validates the quantity
// at its own edge (@Positive), so this is a plain carrier; the boundary check lives with the owner.
public record ReserveRequest(int quantity) {
}
