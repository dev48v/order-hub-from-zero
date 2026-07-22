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
    private final StockResultPublisher publisher;

    public OrderPlacedListener(InventoryService inventoryService, ReservationLedger ledger,
                               StockResultPublisher publisher) {
        this.inventoryService = inventoryService;
        this.ledger = ledger;
        this.publisher = publisher;
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
            Reservation reservation;
            try {
                // The reaction: reserve `quantity` units of the ordered SKU. This is the same domain call
                // the Feign endpoint used to invoke — now driven by an event instead of an HTTP request.
                StockItem item = inventoryService.reserve(event.item(), event.quantity());
                reservation = Reservation.reserved(orderId, event.eventId(), event.item(),
                        event.quantity(), item.available());
                log.info("Reserved {} x {} for order {} - {} units remaining",
                        event.quantity(), event.item(), orderId, item.available());
            } catch (InsufficientStockException e) {
                // GRACEFUL BUSINESS OUTCOME: not enough on hand. Do NOT rethrow — this is an EXPECTED result,
                // not a technical failure, so it must never be retried or land on the DLT. Record it and move
                // on; the saga compensates off this mark.
                reservation = Reservation.failed(orderId, event.eventId(), event.item(),
                        event.quantity(), "INSUFFICIENT_STOCK");
                log.warn("Insufficient stock to reserve {} x {} for order {}: {}",
                        event.quantity(), event.item(), orderId, e.getMessage());
            } catch (UnknownSkuException e) {
                // GRACEFUL BUSINESS OUTCOME: the order names a SKU this service doesn't stock. Same policy —
                // an expected result, mark it, don't crash and don't dead-letter.
                reservation = Reservation.failed(orderId, event.eventId(), event.item(),
                        event.quantity(), "UNKNOWN_SKU");
                log.warn("Unknown SKU '{}' on order {} - cannot reserve", event.item(), orderId);
            }

            // Record the outcome in the ledger, then Day 28: ANSWER with a StockReserved event so the
            // choreography saga hears the result — RESERVED completes the stock leg, a failure lets the saga
            // compensate. The publish runs exactly once per order (we returned early on a duplicate claim
            // above) and swallows its own errors, so it can never crash or loop the consumer.
            ledger.record(reservation);
            publisher.publish(StockReservedEvent.from(reservation));
        } catch (RuntimeException ex) {
            // Day 31 — a TECHNICAL/unexpected failure (NOT a business outcome: those are caught above and
            // never reach here). Release the idempotency claim so the retry can genuinely re-process this
            // record, then RETHROW so the container's DefaultErrorHandler retries it with backoff and, once
            // the attempts are exhausted, routes it to the order-placed.DLT dead-letter topic instead of
            // silently swallowing the failure or looping the partition.
            ledger.unclaim(orderId);
            throw ex;
        }
    }
}
