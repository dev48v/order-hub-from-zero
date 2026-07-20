package dev.dev48v.inventory.config;

import dev.dev48v.inventory.events.StockReservedEvent;
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

// Day 28 — the PRODUCER side of inventory-service, new today and mirroring payment-service's KafkaProducerConfig.
// Until now inventory-service only CONSUMED (Day 26's KafkaConsumerConfig). The choreography saga makes it
// ANSWER: after reserving (or failing to reserve) stock it PUBLISHES a StockReserved result to the
// inventory-events topic, which the order saga waits on. Three things a producer needs — WHERE the broker is,
// HOW to turn key + value into bytes, and a template to send — wired explicitly so the StockReservedEvent type
// is baked into the KafkaTemplate's generics, making the publisher's injection unambiguous and documenting
// exactly what travels on this topic.
@Configuration
public class KafkaProducerConfig {

    // WHERE the broker is — externalized, never hardcoded: the SAME key the consumer reads, so producer and
    // consumer always point at the same cluster (KAFKA_BOOTSTRAP_SERVERS, local docker-compose fallback).
    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    // The ProducerFactory holds the shared producer configuration. KEY = String (we key each result by the
    // order id, so all events about one order stay ordered on one partition). VALUE = StockReservedEvent,
    // serialized to JSON so any consumer in any language can read it.
    @Bean
    public ProducerFactory<String, StockReservedEvent> stockEventProducerFactory() {
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

    // KafkaTemplate is the high-level send API. Typed to StockReservedEvent so StockResultPublisher can inject
    // KafkaTemplate<String, StockReservedEvent> with no ambiguity. Defining our own template makes Boot's
    // auto-configured one back off (it is @ConditionalOnMissingBean).
    @Bean
    public KafkaTemplate<String, StockReservedEvent> stockEventKafkaTemplate(
            ProducerFactory<String, StockReservedEvent> stockEventProducerFactory) {
        return new KafkaTemplate<>(stockEventProducerFactory);
    }
}
