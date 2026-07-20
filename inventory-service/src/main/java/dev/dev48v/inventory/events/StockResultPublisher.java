package dev.dev48v.inventory.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

// Day 28 — publishes inventory-service's StockReserved result to the inventory-events topic. It is the mirror
// of payment-service's PaymentResultPublisher and order-service's SagaResultPublisher, and it follows the same
// golden rule for a producer that runs INSIDE a consumer: publishing must never crash the listener. By the
// time we publish, the reservation has ALREADY been made and recorded in the ledger — a hiccup talking to
// Kafka must not undo that or make the OrderPlaced consumer loop on the record. So every failure path here is
// swallowed and logged, and the consumer's offset still commits:
//   • enabled=false  -> skip entirely (the hermetic non-Kafka tests / a broker-less boot).
//   • send() throws synchronously (broker down -> metadata timeout, capped by MAX_BLOCK_MS) -> caught.
//   • the async delivery later fails -> logged in whenComplete, never rethrown.
// (Making these writes RELIABLE — never silently lost — is Day 30's transactional-outbox job; today we keep
// the producer strictly non-blocking for the consume path.)
@Component
public class StockResultPublisher {

    private static final Logger log = LoggerFactory.getLogger(StockResultPublisher.class);

    private final KafkaTemplate<String, StockReservedEvent> template;
    private final InventoryEventProperties properties;

    public StockResultPublisher(KafkaTemplate<String, StockReservedEvent> template,
                                InventoryEventProperties properties) {
        this.template = template;
        this.properties = properties;
    }

    // Announce the outcome of a reservation attempt. Keyed by order id so every event about one order stays
    // ordered on one partition. Called for BOTH a successful RESERVED and the failure outcomes, because the
    // saga needs to hear a failure to compensate — otherwise it would wait forever for a reserve that won't come.
    public void publish(StockReservedEvent event) {
        if (!properties.enabled()) {
            log.debug("Inventory event publishing disabled - not emitting StockReserved for {}", event.orderId());
            return;
        }
        String topic = properties.stockEventsTopic();
        try {
            template.send(topic, event.orderId(), event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.warn("Failed to publish StockReserved({}) for order {} to topic '{}': {}",
                                    event.outcome(), event.orderId(), topic, ex.toString());
                        } else {
                            log.info("Published StockReserved({}) for order {} to {}-{}@{}",
                                    event.outcome(), event.orderId(), topic,
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (Exception ex) {
            log.warn("Could not publish StockReserved for order {} (reservation already recorded): {}",
                    event.orderId(), ex.toString());
        }
    }
}
