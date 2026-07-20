package dev.dev48v.orderhub.saga;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

// Day 28 — publishes the saga's two TERMINAL facts: OrderShipped (happy path) to the order-shipped topic,
// and OrderCancelled (compensation) to the order-cancelled topic. It is the mirror of order-service's Day-25
// OrderEventPublisher and payment-service's Day-27 PaymentResultPublisher, and it follows the same golden
// rule for a producer that runs INSIDE a consumer: publishing must never crash the listener. By the time we
// publish, the order's status has ALREADY been changed in the repository (shipped or cancelled) — a hiccup
// talking to Kafka must not undo that or make the consumer loop on the record. So every failure path here is
// swallowed and logged, and the consumer's offset still commits:
//   • enabled=false  -> skip entirely (tests / a broker-less environment).
//   • send() throws synchronously (broker down -> metadata timeout, capped by MAX_BLOCK_MS) -> caught.
//   • the async delivery later fails -> logged in whenComplete, never rethrown.
// (Making these writes RELIABLE — never silently lost — is Day 30's transactional-outbox job; today we keep
// the producer strictly non-blocking for the consume path.)
@Component
public class SagaResultPublisher {

    private static final Logger log = LoggerFactory.getLogger(SagaResultPublisher.class);

    private final KafkaTemplate<String, OrderShippedEvent> shippedTemplate;
    private final KafkaTemplate<String, OrderCancelledEvent> cancelledTemplate;
    private final SagaEventProperties properties;

    public SagaResultPublisher(KafkaTemplate<String, OrderShippedEvent> shippedTemplate,
                               KafkaTemplate<String, OrderCancelledEvent> cancelledTemplate,
                               SagaEventProperties properties) {
        this.shippedTemplate = shippedTemplate;
        this.cancelledTemplate = cancelledTemplate;
        this.properties = properties;
    }

    // Announce a shipped order. Keyed by order id so every event about one order stays ordered on one partition.
    public void publishShipped(OrderShippedEvent event) {
        if (!properties.enabled()) {
            log.debug("Saga publishing disabled - not emitting OrderShipped for {}", event.orderId());
            return;
        }
        String topic = properties.orderShippedTopic();
        try {
            shippedTemplate.send(topic, event.orderId(), event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("Failed to publish OrderShipped for order {} to topic '{}': {}",
                                    event.orderId(), topic, ex.toString());
                        } else {
                            log.info("Published OrderShipped for order {} to {}-{}@{}",
                                    event.orderId(), topic,
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (Exception ex) {
            log.warn("Could not publish OrderShipped for order {} (order already shipped): {}",
                    event.orderId(), ex.toString());
        }
    }

    // Announce a compensating cancellation. inventory-service reacts to this to release the reserved stock.
    public void publishCancelled(OrderCancelledEvent event) {
        if (!properties.enabled()) {
            log.debug("Saga publishing disabled - not emitting OrderCancelled for {}", event.orderId());
            return;
        }
        String topic = properties.orderCancelledTopic();
        try {
            cancelledTemplate.send(topic, event.orderId(), event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("Failed to publish OrderCancelled for order {} to topic '{}': {}",
                                    event.orderId(), topic, ex.toString());
                        } else {
                            log.info("Published OrderCancelled({}) for order {} to {}-{}@{}",
                                    event.reason(), event.orderId(), topic,
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (Exception ex) {
            log.warn("Could not publish OrderCancelled for order {} (order already cancelled): {}",
                    event.orderId(), ex.toString());
        }
    }
}
