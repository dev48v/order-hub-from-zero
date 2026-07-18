package dev.dev48v.payment.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

// Day 27 — publishes the PaymentProcessed result to the payment-events topic, the mirror of order-service's
// OrderEventPublisher (Day 25). The golden rule for a producer that runs INSIDE a consumer: publishing the
// result must never crash the listener. The payment has already been decided and recorded on the ledger — a
// hiccup talking to Kafka must not turn that into a redelivered, re-charged order. So every failure path here
// is swallowed and logged, and the consumer's offset still commits:
//   • enabled=false  -> skip entirely (tests / a broker-less environment).
//   • send() throws synchronously (broker down -> metadata timeout, capped at 2s by MAX_BLOCK_MS) -> caught.
//   • the async delivery later fails -> logged in whenComplete, never rethrown.
// (Making the result write itself RELIABLE — never silently lost — is exactly Day 30's transactional-outbox
// job; today we ship the producer and keep it strictly non-blocking for the consume path.)
@Component
public class PaymentResultPublisher {

    private static final Logger log = LoggerFactory.getLogger(PaymentResultPublisher.class);

    private final KafkaTemplate<String, PaymentProcessedEvent> kafkaTemplate;
    private final PaymentEventProperties properties;

    public PaymentResultPublisher(KafkaTemplate<String, PaymentProcessedEvent> kafkaTemplate,
                                  PaymentEventProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    // Emit a PaymentProcessed. Keyed by the order id so every event about one order lands on the same partition
    // (per-order ordering); the value is the immutable result event, serialized to JSON by the configured producer.
    public void publish(PaymentProcessedEvent event) {
        if (!properties.enabled()) {
            log.debug("Event publishing disabled - not emitting PaymentProcessed for {}", event.orderId());
            return;
        }

        String topic = properties.paymentEventsTopic();
        try {
            kafkaTemplate.send(topic, event.orderId(), event)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            // Async delivery failed (broker went away, timeout). The payment is already decided
                            // and recorded; log and move on — never propagate to the consume flow.
                            log.warn("Failed to publish PaymentProcessed for order {} to topic '{}': {}",
                                    event.orderId(), topic, ex.toString());
                        } else {
                            log.info("Published PaymentProcessed({}) for order {} to {}-{}@{}",
                                    event.status(), event.orderId(), topic,
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        }
                    });
        } catch (Exception ex) {
            // Synchronous failure (e.g. broker unreachable -> metadata fetch timed out within max.block.ms).
            // Swallow it: the payment decision stands and the consumer must not loop on a poison record.
            log.warn("Could not publish PaymentProcessed for order {} (decision still recorded): {}",
                    event.orderId(), ex.toString());
        }
    }
}
