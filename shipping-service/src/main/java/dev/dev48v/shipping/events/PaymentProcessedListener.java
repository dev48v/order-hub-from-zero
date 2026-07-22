package dev.dev48v.shipping.events;

import dev.dev48v.shipping.shipment.Shipment;
import dev.dev48v.shipping.shipment.ShipmentLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// Day 28 — the CHOREOGRAPHY SAGA's final step, and where "no central orchestrator" becomes concrete. There is
// no coordinator telling services what to do next; shipping-service simply REACTS to a fact it consumes
// (PaymentProcessed) and, based on the payment decision, states the NEXT fact — which other services then
// react to in turn. The whole saga is that chain of reactions:
//
//   order-service   OrderPlaced ─────────────► inventory-service reserves stock
//                                        └────► payment-service decides payment ─► PaymentProcessed
//   shipping-service  reads PaymentProcessed ─► APPROVED  ─► SHIP:  emit ShipmentScheduled (order CONFIRMED)
//                                            └► DECLINED  ─► COMPENSATE: emit OrderCancelled
//   inventory-service reads OrderCancelled ───► RELEASE the reserved stock (the compensating action)
//
// The KEY CONCEPT: there is NO distributed transaction spanning inventory + payment + shipping. Each service
// committed its own local change and announced it. When a later step fails (payment declined) we do NOT roll
// anything back across services — we can't, the transactions are already committed. Instead we run a
// COMPENSATING action: emit OrderCancelled, and let inventory-service undo its reservation by releasing stock.
// Forward actions have compensating actions; events trigger both.
//
// Two production-shaping properties, same as the Day 26/27 consumers:
//   • IDEMPOTENT — Kafka is AT-LEAST-ONCE, so the same PaymentProcessed can arrive twice. We claim the order id
//     (ShipmentLedger.claim) and settle each order exactly once; a redelivery is skipped before shipping or
//     compensating again (which would double-ship or double-release).
//   • NON-CRASHING — the decision is a pure branch on the event's status, no business exception, and publishing
//     the next event swallows its own failures — so a poison record can't wedge the partition.
@Component
public class PaymentProcessedListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessedListener.class);

    private final ShipmentLedger ledger;
    private final ShippingEventPublisher publisher;

    public PaymentProcessedListener(ShipmentLedger ledger, ShippingEventPublisher publisher) {
        this.ledger = ledger;
        this.publisher = publisher;
    }

    // Subscribe to the payment-events topic. topics/groupId resolve from shipping.events.* (with literal
    // defaults) so they retune per environment without a recompile, and match what payment-service publishes
    // to. containerFactory names the JSON-typed factory from KafkaConsumerConfig. autoStartup is bound to the
    // enabled switch: when shipping.events.enabled=false the container never starts, so the service boots
    // without touching a broker — exactly how the non-Kafka smoke test stays hermetic.
    @KafkaListener(
            topics = "${shipping.events.payment-events-topic:payment-events}",
            groupId = "${shipping.events.consumer-group-id:shipping-service}",
            containerFactory = "paymentEventListenerContainerFactory",
            autoStartup = "${shipping.events.enabled:true}")
    public void onPaymentProcessed(PaymentProcessedEvent event) {
        String orderId = event.orderId();

        // IDEMPOTENCY: claim the order id atomically. If it was already settled (a redelivery), skip —
        // shipping or compensating again would double-ship or double-release stock.
        if (!ledger.claim(orderId)) {
            log.info("Duplicate PaymentProcessed for order {} (event {}) - already settled, skipping",
                    orderId, event.eventId());
            return;
        }

        try {
            if (event.isApproved()) {
                // HAPPY PATH: payment approved (and stock was reserved upstream on OrderPlaced) -> the order is
                // fulfilled. Mark it CONFIRMED and SHIP it, then announce the fact so anyone downstream reacts.
                Shipment shipment = Shipment.shipped(orderId, event.customer(), event.amount());
                ledger.record(shipment);
                publisher.publishShipmentScheduled(ShipmentScheduledEvent.from(shipment, event.eventId()));
                log.info("Order {} CONFIRMED and shipped ({}) - amount {}, tracing {}",
                        orderId, shipment.getTrackingNumber(), event.amount(), event.eventId());
            } else {
                // COMPENSATION PATH: payment declined -> the order cannot be fulfilled, but stock was already
                // reserved. Mark the order CANCELLED and emit the COMPENSATING event so inventory-service
                // RELEASES the reserved stock. This is an EXPECTED business OUTCOME, not an error: it is never
                // thrown, so it is never retried or dead-lettered. No distributed rollback — just a fact that
                // triggers the undo.
                Shipment shipment = Shipment.cancelled(orderId, event.customer(), event.reason());
                ledger.record(shipment);
                publisher.publishOrderCancelled(
                        OrderCancelledEvent.forDeclinedPayment(orderId, event.customer(), event.reason(),
                                event.eventId()));
                log.warn("Order {} payment DECLINED ({}) - COMPENSATING: order CANCELLED, emitting OrderCancelled "
                        + "so inventory-service releases the reserved stock (tracing {})",
                        orderId, event.reason(), event.eventId());
            }
        } catch (RuntimeException ex) {
            // Day 31 — a TECHNICAL/unexpected failure (NOT a decline: that is the compensation branch above).
            // Release the idempotency claim so the retry re-processes, then RETHROW so the DefaultErrorHandler
            // retries with backoff and, once exhausted, routes the record to payment-events.DLT instead of
            // swallowing it or looping the partition.
            ledger.unclaim(orderId);
            throw ex;
        }
    }
}
