package dev.dev48v.notification.events;

import java.math.BigDecimal;
import java.time.Instant;

// Day 41 — notification-service's OWN consumer-side view of the ShipmentScheduled event (a.k.a. OrderShipped)
// that shipping-service (Day 28) publishes to the shipping-events topic when a payment is approved and the
// order ships. shipping-service's own comment even names "a notification service emailing a tracking link" as
// the intended consumer — this is that service. Same discipline: no shared compiled type, just the JSON
// contract. This record mirrors shipping-service's emitted field names EXACTLY (eventId, orderId, customer,
// amount, orderStatus, trackingNumber, causedByEventId, occurredAt) so Jackson binds the incoming JSON onto it.
//
// notification-service reads this HAPPY-path fact and sends the customer the "your order has shipped" message,
// including the tracking number so the note is actionable.
public record ShipmentScheduledEvent(
        String eventId,          // unique id of the ShipmentScheduled emission — the idempotency key for de-duping notifications
        String orderId,          // which order shipped — shown in the message
        String customer,         // who it is for — the notification recipient
        BigDecimal amount,       // the charged amount — carried through for the shipment/receipt note
        String orderStatus,      // the order's resulting status — CONFIRMED
        String trackingNumber,   // the shipment tracking number — included in the message so it is actionable
        String causedByEventId,  // the PaymentProcessed eventId that triggered this — for end-to-end tracing
        Instant occurredAt       // when shipping-service scheduled the shipment
) {
}
