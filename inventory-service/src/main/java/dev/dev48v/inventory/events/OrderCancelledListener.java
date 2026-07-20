package dev.dev48v.inventory.events;

import dev.dev48v.inventory.stock.InventoryService;
import dev.dev48v.inventory.stock.UnknownSkuException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Optional;

// Day 28 — the COMPENSATION consumer, the inventory half of "how the saga rolls back". When the order saga
// cancels an order (payment declined, or stock couldn't be reserved) it emits OrderCancelled to the
// order-cancelled topic. This listener REACTS by RELEASING the stock inventory-service had held for that
// order — the inverse of the reservation it made on OrderPlaced. There is no orchestrator telling it to do
// this; it just reacts to a fact, which is the essence of choreography, and there is no shared transaction to
// undo, so the "rollback" is a compensating OPERATION, not a DB rollback.
//
// Two production-shaping properties, the same discipline as the Day-26 OrderPlaced consumer:
//   • IDEMPOTENT — Kafka delivers AT-LEAST-ONCE, so the same OrderCancelled can arrive twice. ReservationLedger
//     .releaseIfReserved flips the entry from RESERVED to RELEASED ATOMICALLY and only returns a reservation the
//     FIRST time; a redelivery finds it already RELEASED and returns empty, so the stock is put back EXACTLY
//     ONCE and never double-replenished. A cancel for an order we never reserved is likewise a harmless no-op.
//   • NON-CRASHING — releasing is driven off the ledger's own record of what we reserved, so it can't be
//     poisoned by a bad payload; any unexpected error is caught and logged so the container commits the offset
//     and keeps consuming instead of looping on the record.
@Component
public class OrderCancelledListener {

    private static final Logger log = LoggerFactory.getLogger(OrderCancelledListener.class);

    private final InventoryService inventoryService;
    private final ReservationLedger ledger;

    public OrderCancelledListener(InventoryService inventoryService, ReservationLedger ledger) {
        this.inventoryService = inventoryService;
        this.ledger = ledger;
    }

    // Subscribe to the order-cancelled topic. topics/groupId resolve from inventory.events.* (with literal
    // defaults) so they retune per environment without a recompile and match what order-service publishes to.
    // containerFactory names the JSON-typed factory built for OrderCancelledEvent in KafkaConsumerConfig.
    // autoStartup is bound to the same enabled switch as the reserve consumer, so a broker-less boot / the
    // hermetic tests never start this container either.
    @KafkaListener(
            topics = "${inventory.events.order-cancelled-topic:order-cancelled}",
            groupId = "${inventory.events.consumer-group-id:inventory-service}",
            containerFactory = "orderCancelledListenerContainerFactory",
            autoStartup = "${inventory.events.enabled:true}")
    public void onOrderCancelled(OrderCancelledEvent event) {
        String orderId = event.orderId();

        // IDEMPOTENT + ATOMIC: flip RESERVED -> RELEASED and get back the reservation as it WAS, or empty if
        // there is nothing to release (never reserved, a failed reservation, or an already-released redelivery).
        Optional<Reservation> released = ledger.releaseIfReserved(orderId);
        if (released.isEmpty()) {
            log.info("OrderCancelled for order {} (reason {}) - nothing reserved to release, ignoring",
                    orderId, event.reason());
            return;
        }

        Reservation reservation = released.get();
        try {
            // Put the units back on hand — the compensation for the earlier reserve(). Driven off what the
            // ledger recorded we actually held, not off the (informational) event payload.
            inventoryService.release(reservation.sku(), reservation.quantity());
            log.info("Released {} x {} for cancelled order {} (reason {}) - stock returned to inventory",
                    reservation.quantity(), reservation.sku(), orderId, event.reason());
        } catch (UnknownSkuException e) {
            // Shouldn't happen (we reserved this SKU earlier), but never crash the consumer if it does.
            log.warn("Cannot release stock for cancelled order {} - unknown SKU '{}'", orderId, reservation.sku());
        } catch (RuntimeException e) {
            log.warn("Unexpected error releasing stock for cancelled order {}: {}", orderId, e.toString());
        }
    }
}
