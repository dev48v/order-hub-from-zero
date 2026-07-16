package dev.dev48v.orderhub.config;

import dev.dev48v.orderhub.events.OrderEventProperties;
import dev.dev48v.orderhub.events.OrderPlacedEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

// Day 25 — the PRODUCER side of Kafka. Three things a producer needs: WHERE the broker is, HOW to turn a
// key + value into bytes, and a template to actually send. We wire them explicitly here (rather than lean
// entirely on Boot's auto-config) so the OrderPlaced type is baked into the KafkaTemplate's generics —
// KafkaTemplate<String, OrderPlacedEvent> — which makes the publisher's injection unambiguous and documents
// exactly what travels on this topic.
//
// @EnableConfigurationProperties binds orderhub.events.* onto OrderEventProperties (the enabled switch +
// the topic name) so it's injectable wherever the events are published.
@Configuration
@EnableConfigurationProperties(OrderEventProperties.class)
public class KafkaProducerConfig {

    // WHERE the broker is. Externalized per Day 7/23's rule — never hardcoded: application.yml sets
    // spring.kafka.bootstrap-servers to ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}, so the address comes
    // from an env var in every real environment and falls back to the local docker-compose broker in dev.
    // (The default here is a second safety net for a context that sets neither.)
    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    // The ProducerFactory holds the shared producer configuration and creates the underlying KafkaProducer.
    // KEY = String (we key each event by the order id, so all events for one order land on the same
    // partition and stay ORDERED). VALUE = OrderPlacedEvent, serialized to JSON so any consumer in any
    // language can read it.
    @Bean
    public ProducerFactory<String, OrderPlacedEvent> orderEventProducerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        // acks=all — the leader waits for the in-sync replicas to acknowledge before the send is considered
        // successful. The strongest durability guarantee; on a single-node dev broker it's just the leader.
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        // NON-BREAKING SAFETY: cap how long a send may BLOCK fetching metadata / waiting for buffer space.
        // Without this, a send to a DOWN broker blocks up to 60s (max.block.ms default) and would freeze
        // order creation. 2s fails fast; the publisher catches the resulting error and lets the order stand.
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3000);
        // Don't stamp Java type headers on the JSON — keeps the payload language-neutral for any consumer.
        config.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaProducerFactory<>(config);
    }

    // KafkaTemplate is the high-level send API (the JdbcTemplate of Kafka). Typed to OrderPlacedEvent so
    // OrderEventPublisher can inject KafkaTemplate<String, OrderPlacedEvent> with no ambiguity. Defining
    // our own template makes Boot's auto-configured one back off (it is @ConditionalOnMissingBean).
    @Bean
    public KafkaTemplate<String, OrderPlacedEvent> orderEventKafkaTemplate(
            ProducerFactory<String, OrderPlacedEvent> orderEventProducerFactory) {
        return new KafkaTemplate<>(orderEventProducerFactory);
    }
}
