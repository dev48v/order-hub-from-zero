package dev.dev48v.notification.events;

import java.math.BigDecimal;
import java.time.Instant;

// Day 41 — notification-service's OWN consumer-side view of the PaymentProcessed event that payment-service
// (Day 27) publishes to the payment-events topic — the SAME contract shipping-service already consumes. The
// services do not share a compiled type; they share the JSON shape. This record mirrors payment-service's
// emitted field names EXACTLY (eventId, orderId, customer, amount, status, reason, causedByEventId, occurredAt)
// so Jackson binds the incoming JSON straight onto it.
//
// notification-service reads the payment DECISION here and sends the customer the matching message: a "payment
// received" note when APPROVED, or a "payment could not be processed" note (carrying the reason) when DECLINED.
public record PaymentProcessedEvent(
        String eventId,          // unique id of the PaymentProcessed emission — the idempotency key for de-duping notifications
        String orderId,          // which order was charged — shown in the message
        String customer,         // who was charged — the notification recipient
        BigDecimal amount,       // how much was charged — shown on the "payment received" message
        String status,           // APPROVED | DECLINED — selects the message template
        String reason,           // APPROVED | AMOUNT_OVER_LIMIT | TEST_CARD_DECLINED — carried into a decline notice
        String causedByEventId,  // the OrderPlaced eventId that ultimately triggered this — end-to-end tracing
        Instant occurredAt       // when payment-service produced this result
) {

    // The APPROVED / DECLINED fork, in one place so the template selection reads cleanly.
    public boolean isApproved() {
        return "APPROVED".equals(status);
    }
}
