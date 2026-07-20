package dev.dev48v.orderhub.saga;

import dev.dev48v.orderhub.domain.Order;

import java.time.Instant;
import java.util.UUID;

// Day 28 — the COMPENSATING fact of the choreography saga, and the heart of "how compensation works". A
// distributed saga has no shared database transaction that could ROLL BACK across services. Instead, when a
// step fails, the saga UNDOES the earlier steps by emitting a compensating EVENT that each participant
// reacts to. That is exactly this: when payment is DECLINED (or stock could not be reserved), the order saga
// cancels its own order and publishes OrderCancelled to the "order-cancelled" topic. inventory-service
// subscribes to it and RELEASES (un-reserves) the stock it had held — the compensation for its earlier
// reserve. No two-phase commit, no distributed lock: a forward event advanced the saga, and a compensating
// event walks it back.
//
// It carries what a compensator needs to act without calling back: the order id, the SKU + quantity to
// release, and a machine-readable reason for the cancellation. Serialized to JSON with NO type headers.
public record OrderCancelledEvent(
        String eventId,     // unique id of THIS OrderCancelled emission
        String orderId,     // which order was cancelled — inventory keys its release off this
        String sku,         // the SKU whose reservation should be released
        int quantity,       // how many units were reserved (informational; inventory releases what it holds)
        String reason,      // PAYMENT_DECLINED | STOCK_UNAVAILABLE
        Instant occurredAt  // when the saga compensated
) {

    // Build the compensating fact from the cancelled order plus the reason the saga is compensating.
    public static OrderCancelledEvent from(Order order, String reason) {
        return new OrderCancelledEvent(
                UUID.randomUUID().toString(),
                order.getId(),
                order.getItem(),
                order.getQuantity(),
                reason,
                Instant.now());
    }
}
