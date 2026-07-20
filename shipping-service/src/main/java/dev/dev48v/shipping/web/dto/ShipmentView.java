package dev.dev48v.shipping.web.dto;

import dev.dev48v.shipping.shipment.Shipment;

import java.math.BigDecimal;
import java.time.Instant;

// Day 28 — the API response shape for a settled order. A DTO separate from the Shipment domain object (the
// same request/response-separation discipline the other services use): the wire contract can evolve
// independently of the internal model, and we choose exactly which fields to expose. It surfaces both the
// shipment status (SHIPPED | CANCELLED) and the order's resulting status (CONFIRMED | CANCELLED), so a caller
// can see, at a glance, how the saga marked the order.
public record ShipmentView(
        String shipmentId,
        String orderId,
        String customer,
        BigDecimal amount,
        String status,          // SHIPPED | CANCELLED (the shipment's own status)
        String orderStatus,     // CONFIRMED | CANCELLED (how the saga marked the order)
        String trackingNumber,  // present when shipped, null when cancelled
        String reason,          // present when cancelled (the payment decline reason), null when shipped
        Instant createdAt) {

    public static ShipmentView from(Shipment s) {
        return new ShipmentView(s.getShipmentId(), s.getOrderId(), s.getCustomer(), s.getAmount(),
                s.getStatus().name(), s.orderStatus(), s.getTrackingNumber(), s.getReason(), s.getCreatedAt());
    }
}
