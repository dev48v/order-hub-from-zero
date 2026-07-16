package dev.dev48v.orderhub.events;

import dev.dev48v.orderhub.domain.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

// Day 25 — publishes the OrderPlaced event. This is the seam between the synchronous world (a request
// created an order) and the asynchronous, event-driven world (Phase 4). OrderService calls this AFTER the
// order is safely saved, so an event is only ever emitted for an order that truly exists.
//
// The golden rule for a FIRST producer: publishing must be ADDITIVE and must never break order creation.
// An order was already accepted and persisted — a hiccup talking to Kafka must not undo that or 500 the
// caller. So every failure path here is swallowed and logged, and the order stands:
//   • enabled=false  -> skip entirely (tests / a broker-less environment).
//   • send() throws synchronously (broker down -> metadata timeout, capped at 2s by MAX_BLOCK_MS) -> caught.
//   • the async delivery later fails -> logged in whenComplete, never rethrown.
// (Making the write itself RELIABLE — never silently lost — is exactly the job of Day 30's transactional
// outbox pattern; today we ship the producer and keep it strictly non-blocking for the create flow.)
@Component
public class OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderEventPublisher.class);

    private final KafkaTemplate<String, OrderPlacedEvent> kafkaTemplate;
    private final OrderEventProperties properties;

    public OrderEventPublisher(KafkaTemplate<String, OrderPlacedEvent> kafkaTemplate,
                               OrderEventProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    // Emit an OrderPlaced for a just-saved order. Keyed by the order id so every event about one order
    // lands on the same partition (per-order ordering); the value is the immutable event, serialized to
    // JSON by the configured producer.
    public void publishOrderPlaced(Order order) {
        if (!properties.enabled()) {
            log.debug("Event publishing disabled - not emitting OrderPlaced for {}", order.getId());
            return;
        }

        OrderPlacedEvent event = OrderPlacedEvent.from(order);
        String topic = properties.orderPlacedTopic();
        try {
            kafkaTemplate.send(topic, order.getId(), event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            // Async delivery failed (broker went away, timeout). The order already exists;
                            // log and move on — never propagate to the create flow.
                            log.warn("Failed to publish OrderPlaced for order {} to topic '{}': {}",
                                    order.getId(), topic, ex.toString());
                        } else {
                            log.info("Published OrderPlaced for order {} to {}-{}@{}",
                                    order.getId(), topic,
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (Exception ex) {
            // Synchronous failure (e.g. broker unreachable -> metadata fetch timed out within max.block.ms).
            // Swallow it: order creation must not fail because the event bus is momentarily unavailable.
            log.warn("Could not publish OrderPlaced for order {} (order creation still succeeded): {}",
                    order.getId(), ex.toString());
        }
    }
}
