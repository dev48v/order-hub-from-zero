package dev.dev48v.shipping.shipment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// Day 28 — the shipping context's core domain object: the record of what the saga decided for ONE order.
// Like inventory-service's StockItem and payment-service's Payment, it is a class WITH behaviour rather than a
// bare data holder, because the rule lives with the data it guards: an order is either SHIPPED or CANCELLED,
// decided exactly once, and the two factory methods are the only ways to create it — there is no setter to
// flip a shipped order to cancelled afterwards.
//
// This is the shipping context's OWN model — it looks nothing like Order, StockItem or Payment, and that is
// correct: each bounded context models the world in the terms that matter to IT. Shipping cares about whether
// the order ships, its tracking number, and (for a declined order) why it was cancelled. It also records the
// resulting ORDER STATUS — CONFIRMED when shipped, CANCELLED when compensated — which is the "mark the order"
// side of the saga, held here as shipping-service's own projection of the order's fate.
public class Shipment {

    private final String shipmentId;       // this shipment's own id
    private final String orderId;          // the order this is about — the saga's idempotency key
    private final String customer;         // who the order is for
    private final BigDecimal amount;       // the charged amount, carried through from the payment
    private final ShipmentStatus status;   // SHIPPED | CANCELLED — decided once
    private final String trackingNumber;   // the shipment tracking number; null for a cancelled order
    private final String reason;           // why it was cancelled (payment decline reason); null when shipped
    private final Instant createdAt;

    private Shipment(String orderId, String customer, BigDecimal amount, ShipmentStatus status,
                     String trackingNumber, String reason) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId must not be blank");
        }
        this.shipmentId = UUID.randomUUID().toString();
        this.orderId = orderId;
        this.customer = customer;
        this.amount = amount;
        this.status = status;
        this.trackingNumber = trackingNumber;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    // HAPPY path: the payment was approved and stock was reserved, so the order ships. A tracking number is
    // minted here — the tangible proof the order was fulfilled. The order's resulting status is CONFIRMED.
    public static Shipment shipped(String orderId, String customer, BigDecimal amount) {
        String tracking = "SHIP-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return new Shipment(orderId, customer, amount, ShipmentStatus.SHIPPED, tracking, null);
    }

    // COMPENSATION path: the payment was declined, so the order cannot be fulfilled. No tracking number; the
    // reason carries the payment decline reason for the audit trail. The order's resulting status is CANCELLED.
    public static Shipment cancelled(String orderId, String customer, String reason) {
        return new Shipment(orderId, customer, null, ShipmentStatus.CANCELLED, null, reason);
    }

    // The order's resulting status as the saga sees it: a shipped order is CONFIRMED, a cancelled one CANCELLED.
    // This is the "mark the order CONFIRMED / CANCELLED" side of the saga, expressed as shipping-service's own
    // projection (each service owns its own view; order-service's aggregate would sync off the emitted events).
    public String orderStatus() {
        return status == ShipmentStatus.SHIPPED ? "CONFIRMED" : "CANCELLED";
    }

    public boolean isShipped() {
        return status == ShipmentStatus.SHIPPED;
    }

    public String getShipmentId() { return shipmentId; }
    public String getOrderId() { return orderId; }
    public String getCustomer() { return customer; }
    public BigDecimal getAmount() { return amount; }
    public ShipmentStatus getStatus() { return status; }
    public String getTrackingNumber() { return trackingNumber; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
