package dev.dev48v.shipping.events;

import java.time.Instant;
import java.util.UUID;

// Day 28 — the COMPENSATING fact, the beating heart of the saga. When a payment is DECLINED, the order can
// no longer be fulfilled — but by now inventory-service has ALREADY reserved stock for it (on the OrderPlaced,
// Day 26). A classic distributed-transaction problem: two services changed state, and there is no shared
// database transaction to roll them both back. A SAGA solves it WITHOUT a distributed transaction: every
// forward action has a COMPENSATING action, and the way you trigger a compensation is to publish another
// event. This is that event.
//
// shipping-service emits OrderCancelled to the order-cancelled topic; inventory-service SUBSCRIBES to it and,
// in reaction, RELEASES the units it reserved (putting them back on the shelf) — undoing the forward step
// with a compensating step, driven by a fact, not a command or a rollback. The event also announces to the
// whole system that the order is cancelled, so order-service's status projection can mark it CANCELLED.
//
// It is a FACT in the past tense ("OrderCancelled"), immutable, self-contained, timestamped. `reason` carries
// WHY (the payment decline reason) for the audit trail; `causedByEventId` is the PaymentProcessed eventId that
// forced the compensation, for end-to-end tracing. Serialized to JSON with NO type headers, like every other
// producer — so inventory-service (a different codebase) decodes it purely from the shared JSON contract.
public record OrderCancelledEvent(
        String eventId,          // unique id of THIS OrderCancelled emission
        String orderId,          // which order is being cancelled — the key inventory-service releases against
        String customer,         // who the order was for
        String reason,           // WHY it was cancelled (the payment decline reason: AMOUNT_OVER_LIMIT, …)
        String causedByEventId,  // the PaymentProcessed eventId that forced this compensation — for tracing
        Instant occurredAt       // when shipping-service decided to compensate
) {

    // Build the compensating fact for a declined order. A brand-new eventId is minted for this emission; the
    // triggering PaymentProcessed eventId is threaded through as causedByEventId.
    public static OrderCancelledEvent forDeclinedPayment(String orderId, String customer, String reason,
                                                         String causedByEventId) {
        return new OrderCancelledEvent(
                UUID.randomUUID().toString(),
                orderId,
                customer,
                reason,
                causedByEventId,
                Instant.now());
    }
}
