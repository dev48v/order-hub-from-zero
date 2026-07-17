package dev.dev48v.inventory.events;

import dev.dev48v.inventory.domain.StockItem;
import dev.dev48v.inventory.stock.InsufficientStockException;
import dev.dev48v.inventory.stock.InventoryService;
import dev.dev48v.inventory.stock.UnknownSkuException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// Day 26 — the first CONSUMER. This is the reaction that used to be a synchronous Feign call from
// order-service (Day 18): now inventory-service SUBSCRIBES to the order-placed topic and reserves stock
// on its own, the moment an OrderPlaced event arrives — with ZERO change to order-service. That is the
// whole payoff of Phase 4's decoupling: order-service states the fact and moves on; reactions are added
// as new consumers, not as new outbound calls the producer has to know about.
//
// Two properties make this robust and production-shaped:
//   • IDEMPOTENT — Kafka delivers AT-LEAST-ONCE, so the same event can arrive twice. We key on the order
//     id and process each order exactly once (ReservationLedger.claim), so a redelivery never double-reserves.
//   • NON-CRASHING — a business failure (not enough stock, unknown SKU) is EXPECTED, not exceptional. If we
//     let it escape, the container would redeliver the same record endlessly (a poison message). Instead we
//     catch it, record the outcome, and return normally so the offset advances. Making the FAILURE itself a
//     first-class signal (a StockReservationFailed event a saga can compensate) is Day 28's job.
@Component
public class OrderPlacedListener {

    private static final Logger log = LoggerFactory.getLogger(OrderPlacedListener.class);

    private final InventoryService inventoryService;
    private final ReservationLedger ledger;

    public OrderPlacedListener(InventoryService inventoryService, ReservationLedger ledger) {
        this.inventoryService = inventoryService;
        this.ledger = ledger;
    }

    // Subscribe to the order-placed topic. topics/groupId resolve from inventory.events.* (with literal
    // defaults) so they can be retuned per environment without a recompile, and match what order-service
    // publishes to. containerFactory names the JSON-typed factory from KafkaConsumerConfig.
    // autoStartup is bound to the enabled switch: when inventory.events.enabled=false the container never
    // starts, so the service boots without touching a broker — exactly how the non-Kafka tests stay hermetic.
    @KafkaListener(
            topics = "${inventory.events.order-placed-topic:order-placed}",
            groupId = "${inventory.events.consumer-group-id:inventory-service}",
            containerFactory = "orderEventListenerContainerFactory",
            autoStartup = "${inventory.events.enabled:true}")
    public void onOrderPlaced(OrderPlacedEvent event) {
        String orderId = event.orderId();

        // IDEMPOTENCY: claim the order id atomically. If it was already handled (a redelivery), skip —
        // reserving again would over-commit stock for a single order.
        if (!ledger.claim(orderId)) {
            log.info("Duplicate OrderPlaced for order {} (event {}) - already processed, skipping",
                    orderId, event.eventId());
            return;
        }

        try {
            // The reaction: reserve `quantity` units of the ordered SKU. This is the same domain call
            // the Feign endpoint used to invoke — now driven by an event instead of an HTTP request.
            StockItem item = inventoryService.reserve(event.item(), event.quantity());
            ledger.record(Reservation.reserved(orderId, event.eventId(), event.item(),
                    event.quantity(), item.available()));
            log.info("Reserved {} x {} for order {} - {} units remaining",
                    event.quantity(), event.item(), orderId, item.available());
        } catch (InsufficientStockException e) {
            // GRACEFUL: not enough on hand. Do NOT rethrow (that would make the container redeliver this
            // record forever). Record the outcome and move on; a saga can compensate off this mark.
            ledger.record(Reservation.failed(orderId, event.eventId(), event.item(),
                    event.quantity(), "INSUFFICIENT_STOCK"));
            log.warn("Insufficient stock to reserve {} x {} for order {}: {}",
                    event.quantity(), event.item(), orderId, e.getMessage());
        } catch (UnknownSkuException e) {
            // GRACEFUL: the order names a SKU this service doesn't stock. Same policy — mark, don't crash.
            ledger.record(Reservation.failed(orderId, event.eventId(), event.item(),
                    event.quantity(), "UNKNOWN_SKU"));
            log.warn("Unknown SKU '{}' on order {} - cannot reserve", event.item(), orderId);
        }
    }
}
