package dev.dev48v.shipping.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

// Day 28 — publishes the saga's NEXT event: the happy-path ShipmentScheduled to shipping-events, or the
// COMPENSATING OrderCancelled to order-cancelled. It is the mirror of order-service's OrderEventPublisher
// (Day 25) and payment-service's PaymentResultPublisher (Day 27), and it obeys the same golden rule for a
// producer that runs INSIDE a consumer: publishing the next event must never crash the listener. By the time
// we publish, the order's fate is already DECIDED and recorded on the ledger — a hiccup talking to Kafka must
// not turn that into a redelivered, re-shipped or re-compensated order. So every failure path is swallowed and
// logged, and the consumer's offset still commits:
//   • enabled=false  -> skip entirely (tests / a broker-less environment).
//   • send() throws synchronously (broker down -> metadata timeout, capped at 2s by MAX_BLOCK_MS) -> caught.
//   • the async delivery later fails -> logged in whenComplete, never rethrown.
// (Making these writes RELIABLE — never silently lost — is exactly Day 30's transactional-outbox job; today we
// ship the producer and keep it strictly non-blocking for the consume path.)
//
// Both events travel on ONE KafkaTemplate<String, Object>: shipping-service emits two DIFFERENT event types to
// two DIFFERENT topics, and a single Object-typed template (JSON value serializer, no type headers) carries
// either without needing a second bean. Everything is keyed by the order id, so all events about one order
// land on the same partition and stay ordered.
@Component
public class ShippingEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(ShippingEventPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ShippingEventProperties properties;

    public ShippingEventPublisher(KafkaTemplate<String, Object> kafkaTemplate,
                                  ShippingEventProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    // Happy path: announce the order shipped/confirmed.
    public void publishShipmentScheduled(ShipmentScheduledEvent event) {
        send(properties.shipmentEventsTopic(), event.orderId(), event,
                "ShipmentScheduled(CONFIRMED)");
    }

    // Compensation path: announce the order cancelled so inventory-service releases the reserved stock.
    public void publishOrderCancelled(OrderCancelledEvent event) {
        send(properties.orderCancelledTopic(), event.orderId(), event,
                "OrderCancelled(compensation)");
    }

    // Shared, strictly non-blocking send. Keyed by order id (per-order ordering); the value is the immutable
    // event, serialized to JSON by the configured producer. Every failure is swallowed so the consume path
    // (which already recorded the decision) never loops on a poison record.
    private void send(String topic, String key, Object event, String label) {
        if (!properties.enabled()) {
            log.debug("Event publishing disabled - not emitting {} for {}", label, key);
            return;
        }
        try {
            kafkaTemplate.send(topic, key, event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            // Async delivery failed (broker went away, timeout). The saga decision stands;
                            // log and move on — never propagate to the consume flow.
                            log.warn("Failed to publish {} for order {} to topic '{}': {}",
                                    label, key, topic, ex.toString());
                        } else {
                            log.info("Published {} for order {} to {}-{}@{}",
                                    label, key, topic,
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (Exception ex) {
            // Synchronous failure (e.g. broker unreachable -> metadata fetch timed out within max.block.ms).
            // Swallow it: the decision is already recorded and the consumer must not loop on a poison record.
            log.warn("Could not publish {} for order {} (decision still recorded): {}",
                    label, key, ex.toString());
        }
    }
}
