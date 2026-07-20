package dev.dev48v.shipping.events;

import dev.dev48v.shipping.shipment.Shipment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// Day 28 — the HAPPY-path fact shipping-service publishes to the shipping-events topic when a payment is
// APPROVED: "the order is confirmed and shipped". Also known as OrderShipped. This is the payoff of the
// choreography's forward path — stock was reserved (inventory-service, Day 26), payment was approved
// (payment-service, Day 27), so the order is now fulfilled. shipping-service states this as a NEW fact and
// moves on; whoever cares — order-service's status projection (mark CONFIRMED/SHIPPED), a notification
// service emailing a tracking link — subscribes. shipping-service neither knows nor cares who reacts.
//
// It carries the whole outcome so a downstream consumer can act WITHOUT calling back: the order id, who it is
// for, the amount charged, the resulting ORDER STATUS (CONFIRMED), and a shipping tracking number. `eventId`
// is this emission's own id; `causedByEventId` is the PaymentProcessed eventId that triggered it, so the saga
// can be traced end to end. Serialized to JSON with NO type headers (language-neutral), exactly like every
// other producer in the system.
public record ShipmentScheduledEvent(
        String eventId,          // unique id of THIS ShipmentScheduled emission
        String orderId,          // which order was shipped
        String customer,         // who it is for
        BigDecimal amount,       // the charged amount carried through for the receipt/notification
        String orderStatus,      // the order's resulting status — CONFIRMED (then shipped)
        String trackingNumber,   // the shipment's tracking number
        String causedByEventId,  // the PaymentProcessed eventId that triggered this — for end-to-end tracing
        Instant occurredAt       // when shipping-service scheduled the shipment
) {

    // Build the fact from a scheduled Shipment plus the id of the PaymentProcessed that caused it.
    public static ShipmentScheduledEvent from(Shipment shipment, String causedByEventId) {
        return new ShipmentScheduledEvent(
                UUID.randomUUID().toString(),
                shipment.getOrderId(),
                shipment.getCustomer(),
                shipment.getAmount(),
                shipment.orderStatus(),        // CONFIRMED
                shipment.getTrackingNumber(),
                causedByEventId,
                Instant.now());
    }
}
