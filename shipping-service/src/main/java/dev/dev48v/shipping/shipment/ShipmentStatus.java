package dev.dev48v.shipping.shipment;

// Day 28 — the terminal fate of an order as the shipping saga sees it. There is no PENDING here: by the time
// shipping-service acts, the payment has already been DECIDED, so the order goes straight to one terminal
// state — SHIPPED (payment approved, stock was reserved, the order is fulfilled) or CANCELLED (payment
// declined, so the saga compensates). Modeling it as a two-value enum keeps the saga's outcome explicit and
// impossible to leave half-decided.
public enum ShipmentStatus {
    SHIPPED,
    CANCELLED
}
