package dev.dev48v.orderhub.config;

import dev.dev48v.orderhub.saga.OrderCancelledEvent;
import dev.dev48v.orderhub.saga.OrderShippedEvent;
import dev.dev48v.orderhub.saga.PaymentProcessedEvent;
import dev.dev48v.orderhub.saga.SagaEventProperties;
import dev.dev48v.orderhub.saga.StockReservedEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

// Day 28 — ALL the Kafka wiring for the choreography saga in one place. Until today order-service only
// PRODUCED (Day 25's KafkaProducerConfig, the OrderPlaced template). The saga makes it a full event-driven
// participant: it now CONSUMES two result topics (inventory-events, payment-events) AND PRODUCES two terminal
// topics (order-shipped, order-cancelled). Each side needs the same three things the earlier configs set up —
// WHERE the broker is, HOW to (de)serialize, and a template/container-factory — so this file mirrors
// inventory-service's KafkaConsumerConfig and payment-service's KafkaProducerConfig, one pair per event type.
//
// @EnableKafka activates @KafkaListener processing (needed in the SLICED saga test, which loads explicit
// @Configuration classes with no auto-config; harmless when Boot's auto-config is also present).
// @EnableConfigurationProperties binds orderhub.saga.* onto SagaEventProperties for the listeners + publisher.
@Configuration
@EnableKafka
@EnableConfigurationProperties(SagaEventProperties.class)
public class SagaKafkaConfig {

    // WHERE the broker is — externalized exactly like every other service (never hardcoded): application.yml
    // sets spring.kafka.bootstrap-servers to ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}, so the same jar reads
    // the address from an env var everywhere and falls back to the local docker-compose broker in dev.
    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    private final SagaEventProperties properties;

    public SagaKafkaConfig(SagaEventProperties properties) {
        this.properties = properties;
    }

    // ---- Shared consumer config -------------------------------------------------------------------------
    // Both saga consumers share the SAME group (orderhub.saga.consumer-group-id) — order-service's OWN group,
    // distinct from inventory-service's and payment-service's, so it gets its own independent copy of every
    // result event. Each @KafkaListener still runs its own container/consumer; they just belong to one group.
    private Map<String, Object> baseConsumerConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.consumerGroupId());
        // Brand-new group with no committed offset → start at the EARLIEST record so no result is missed.
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Spring's container owns offset commits (after processing), not a client-side timer.
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return config;
    }

    // A JSON value deserializer for a concrete event type. The upstream producers stamp NO Java type headers
    // (ADD_TYPE_INFO_HEADERS=false, language-neutral), so we tell the deserializer the target type up front and
    // to IGNORE type headers; addTrustedPackages is the allow-list for what JSON may become (our own packages).
    private <T> JsonDeserializer<T> jsonDeserializer(Class<T> type) {
        JsonDeserializer<T> deserializer = new JsonDeserializer<>(type);
        deserializer.setUseTypeHeaders(false);
        deserializer.addTrustedPackages("dev.dev48v.*");
        return deserializer;
    }

    // ---- Leg 1: inventory-events -> StockReserved -------------------------------------------------------
    @Bean
    public ConsumerFactory<String, StockReservedEvent> stockReservedConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(baseConsumerConfig(),
                new StringDeserializer(), jsonDeserializer(StockReservedEvent.class));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, StockReservedEvent> stockReservedListenerContainerFactory(
            ConsumerFactory<String, StockReservedEvent> stockReservedConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, StockReservedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(stockReservedConsumerFactory);
        return factory;
    }

    // ---- Leg 2: payment-events -> PaymentProcessed ------------------------------------------------------
    @Bean
    public ConsumerFactory<String, PaymentProcessedEvent> paymentProcessedConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(baseConsumerConfig(),
                new StringDeserializer(), jsonDeserializer(PaymentProcessedEvent.class));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PaymentProcessedEvent> paymentProcessedListenerContainerFactory(
            ConsumerFactory<String, PaymentProcessedEvent> paymentProcessedConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, PaymentProcessedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(paymentProcessedConsumerFactory);
        return factory;
    }

    // ---- Shared producer config -------------------------------------------------------------------------
    // Each terminal fact is keyed by order id (per-order ordering on one partition) and serialized to JSON
    // with NO type headers, so any consumer in any language can read it — identical to the Day-25/27 producers.
    private Map<String, Object> baseProducerConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        // NON-BLOCKING SAFETY: cap how long a send may BLOCK on metadata / buffer space so a DOWN broker can't
        // freeze the consumer thread that publishes the terminal fact. 2s fails fast; the publisher swallows it.
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3000);
        config.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return config;
    }

    // ---- Happy path: order-shipped -> OrderShipped ------------------------------------------------------
    @Bean
    public ProducerFactory<String, OrderShippedEvent> orderShippedProducerFactory() {
        return new DefaultKafkaProducerFactory<>(baseProducerConfig());
    }

    @Bean
    public KafkaTemplate<String, OrderShippedEvent> orderShippedKafkaTemplate(
            ProducerFactory<String, OrderShippedEvent> orderShippedProducerFactory) {
        return new KafkaTemplate<>(orderShippedProducerFactory);
    }

    // ---- Compensation: order-cancelled -> OrderCancelled -----------------------------------------------
    @Bean
    public ProducerFactory<String, OrderCancelledEvent> orderCancelledProducerFactory() {
        return new DefaultKafkaProducerFactory<>(baseProducerConfig());
    }

    @Bean
    public KafkaTemplate<String, OrderCancelledEvent> orderCancelledKafkaTemplate(
            ProducerFactory<String, OrderCancelledEvent> orderCancelledProducerFactory) {
        return new KafkaTemplate<>(orderCancelledProducerFactory);
    }
}
