package dev.dev48v.payment.config;

import dev.dev48v.payment.events.PaymentProcessedEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

// Day 27 — the PRODUCER side of payment-service, mirroring order-service's KafkaProducerConfig (Day 25).
// This is what makes payment-service a full event-driven participant rather than a dead-end consumer: after
// deciding an order's payment it PUBLISHES a PaymentProcessed result to the payment-events topic. Three things
// a producer needs: WHERE the broker is, HOW to turn a key + value into bytes, and a template to send. We wire
// them explicitly so the PaymentProcessedEvent type is baked into the KafkaTemplate's generics —
// KafkaTemplate<String, PaymentProcessedEvent> — which makes the publisher's injection unambiguous and
// documents exactly what travels on this topic.
@Configuration
public class KafkaProducerConfig {

    // WHERE the broker is — externalized, never hardcoded: the SAME key the consumer reads, so producer and
    // consumer always point at the same cluster.
    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    // The ProducerFactory holds the shared producer configuration. KEY = String (we key each result by the
    // order id, so all events about one order stay ordered on one partition). VALUE = PaymentProcessedEvent,
    // serialized to JSON so any consumer in any language can read it.
    @Bean
    public ProducerFactory<String, PaymentProcessedEvent> paymentEventProducerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        // acks=all — the strongest durability guarantee; on a single-node dev broker it's just the leader.
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        // NON-BLOCKING SAFETY: cap how long a send may BLOCK on metadata / buffer space so a DOWN broker can't
        // freeze the consumer thread that publishes the result. 2s fails fast; the publisher swallows the error.
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3000);
        // Don't stamp Java type headers on the JSON — keeps the payload language-neutral for any consumer.
        config.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaProducerFactory<>(config);
    }

    // KafkaTemplate is the high-level send API. Typed to PaymentProcessedEvent so PaymentResultPublisher can
    // inject KafkaTemplate<String, PaymentProcessedEvent> with no ambiguity. Defining our own template makes
    // Boot's auto-configured one back off (it is @ConditionalOnMissingBean).
    @Bean
    public KafkaTemplate<String, PaymentProcessedEvent> paymentEventKafkaTemplate(
            ProducerFactory<String, PaymentProcessedEvent> paymentEventProducerFactory) {
        return new KafkaTemplate<>(paymentEventProducerFactory);
    }
}
