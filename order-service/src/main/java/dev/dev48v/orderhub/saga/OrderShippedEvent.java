package dev.dev48v.orderhub.saga;

import dev.dev48v.orderhub.domain.Order;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// Day 28 — the HAPPY-PATH terminal fact of the choreography saga. When the order saga has seen BOTH a
// StockReserved(RESERVED) and a PaymentProcessed(APPROVED) for an order, it confirms and ships the order,
// then announces it with this OrderShipped event on the "order-shipped" topic. This is the "ship" step of
// the roadmap flow order → inventory → payment → ship: it is the saga's SUCCESS output. Downstream reactions
// (a notification service in Day 41, a shipping label printer, an analytics sink) subscribe to it; the saga
// neither knows nor cares who reacts — exactly the decoupling choreography is built on.
//
// It carries the whole outcome so a consumer can act WITHOUT calling back: the order id + line, the amount
// that was charged, and the tracing ids that tie it back to the payment result and, through that, the
// original OrderPlaced. Serialized to JSON with NO type headers, like every other event in the system.
public record OrderShippedEvent(
        String eventId,               // unique id of THIS OrderShipped emission
        String orderId,               // which order shipped
        String customer,              // who it's for
        String item,                  // the item / SKU shipped
        int quantity,                 // how many units
        BigDecimal amount,            // the amount that was charged (from the approved payment)
        String status,                // SHIPPED
        String causedByPaymentEventId,// the PaymentProcessed eventId that completed the saga — for tracing
        Instant occurredAt            // when the saga shipped it
) {

    // Build the shipped fact from the just-shipped order plus the payment amount and the payment event id
    // that completed the saga. A brand-new eventId is minted for every emission.
    public static OrderShippedEvent from(Order order, BigDecimal amount, String causedByPaymentEventId) {
        return new OrderShippedEvent(
                UUID.randomUUID().toString(),
                order.getId(),
                order.getCustomer(),
                order.getItem(),
                order.getQuantity(),
                amount,
                order.getStatus().name(),
                causedByPaymentEventId,
                Instant.now());
    }
}
