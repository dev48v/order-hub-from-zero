package dev.dev48v.shipping.config;

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

// Day 28 — the PRODUCER side of shipping-service, mirroring order-service's (Day 25) and payment-service's
// (Day 27) producer configs. This is what lets shipping-service state the saga's NEXT fact after it reads a
// payment result: ShipmentScheduled on approve, OrderCancelled on decline. Three things a producer needs:
// WHERE the broker is, HOW to turn a key + value into bytes, and a template to send.
//
// ONE difference from the other services: shipping-service emits TWO different event types to TWO different
// topics. Rather than a typed template per type, we wire a single KafkaTemplate<String, Object> — the JSON
// value serializer happily serializes whichever event it is handed, and keeping it Object-typed means the
// publisher needs exactly one bean and there is no injection ambiguity. Everything is keyed by the order id
// (String), so all events about one order land on the same partition and stay ORDERED.
@Configuration
public class KafkaProducerConfig {

    // WHERE the broker is — externalized, never hardcoded: the SAME key the consumer reads, so producer and
    // consumer always point at the same cluster.
    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    // The ProducerFactory holds the shared producer configuration. KEY = String (the order id). VALUE = Object,
    // serialized to JSON so any consumer in any language can read it — one factory carries both event types.
    @Bean
    public ProducerFactory<String, Object> shippingEventProducerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        // acks=all — the strongest durability guarantee; on a single-node dev broker it's just the leader.
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        // NON-BLOCKING SAFETY: cap how long a send may BLOCK on metadata / buffer space so a DOWN broker can't
        // freeze the consumer thread that publishes the next event. 2s fails fast; the publisher swallows it.
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3000);
        // Don't stamp Java type headers on the JSON — keeps the payload language-neutral for any consumer
        // (inventory-service decodes OrderCancelled purely from the shared JSON contract).
        config.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaProducerFactory<>(config);
    }

    // KafkaTemplate is the high-level send API. Typed <String, Object> so ShippingEventPublisher can send
    // EITHER a ShipmentScheduledEvent or an OrderCancelledEvent through the one bean. Defining our own template
    // makes Boot's auto-configured one back off (it is @ConditionalOnMissingBean).
    @Bean
    public KafkaTemplate<String, Object> shippingEventKafkaTemplate(
            ProducerFactory<String, Object> shippingEventProducerFactory) {
        return new KafkaTemplate<>(shippingEventProducerFactory);
    }
}
