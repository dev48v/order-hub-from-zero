package dev.dev48v.inventory.config;

import dev.dev48v.inventory.events.InventoryEventProperties;
import dev.dev48v.inventory.events.OrderCancelledEvent;
import dev.dev48v.inventory.events.OrderPlacedEvent;
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
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

// Day 26 — the CONSUMER side of Kafka, the mirror of order-service's KafkaProducerConfig. A consumer needs
// three things: WHERE the broker is, HOW to turn the bytes back into an object, and a listener-container
// factory that runs the poll loop and hands each record to our @KafkaListener method.
//
// @EnableKafka is what activates @KafkaListener processing. Spring Boot's Kafka auto-config normally turns
// this on for you, but this project's SLICED tests load an explicit set of @Configuration classes (no
// auto-config), so declaring it here guarantees the listener is detected in BOTH the full app and the test.
// (Adding it when auto-config is also present is a harmless, idempotent no-op.)
//
// @EnableConfigurationProperties binds inventory.events.* onto InventoryEventProperties (enabled switch,
// topic name, consumer group) so they're injectable here and in the listener.
@Configuration
@EnableKafka
@EnableConfigurationProperties(InventoryEventProperties.class)
public class KafkaConsumerConfig {

    // WHERE the broker is — externalized exactly like the producer (Day 7/23's never-hardcode rule):
    // application.yml sets spring.kafka.bootstrap-servers to ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092},
    // so the SAME jar reads the address from an env var in every environment and falls back to the local
    // docker-compose broker in dev. The default here is a second safety net.
    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    private final InventoryEventProperties properties;

    public KafkaConsumerConfig(InventoryEventProperties properties) {
        this.properties = properties;
    }

    // The ConsumerFactory holds the shared consumer configuration. KEY = String (the order id the producer
    // keyed by); VALUE = OrderPlacedEvent, rebuilt from the JSON on the topic.
    @Bean
    public ConsumerFactory<String, OrderPlacedEvent> orderEventConsumerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // The consumer GROUP: all instances of inventory-service share one group, so each partition —
        // and thus each event — is handled by exactly one instance. A different service gets its own group.
        config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.consumerGroupId());
        // On a brand-new group with no committed offset, start at the EARLIEST record so we don't miss
        // events produced before this consumer first connected. Once the group has committed offsets it
        // resumes from there; this only governs the very first assignment.
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Let Spring's container own offset commits (commit AFTER a record is processed) rather than the
        // client auto-committing on a timer — offsets then track what we've actually handled.
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        // VALUE deserializer: JSON bytes -> OrderPlacedEvent. The producer stamps NO Java type headers
        // (it set ADD_TYPE_INFO_HEADERS=false to keep the payload language-neutral), so we must tell the
        // deserializer the concrete target type up front and to IGNORE type headers. addTrustedPackages
        // is the safety allow-list for which classes JSON may be deserialized into (never blindly "*" a
        // hostile topic; here we trust our own packages). The producer keys by order id as a plain String.
        JsonDeserializer<OrderPlacedEvent> valueDeserializer = new JsonDeserializer<>(OrderPlacedEvent.class);
        valueDeserializer.setUseTypeHeaders(false);
        valueDeserializer.addTrustedPackages("dev.dev48v.*");

        return new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(), valueDeserializer);
    }

    // The listener-container factory the @KafkaListener references by name. It runs the poll loop, hands
    // each record to onOrderPlaced, and commits offsets after successful processing. Typed to
    // OrderPlacedEvent so the listener method can take the event directly with no manual parsing.
    // Day 31 — the shared DefaultErrorHandler is attached here so a record that keeps FAILING is retried
    // with backoff and, once the attempts are exhausted, routed to the dead-letter topic instead of being
    // silently dropped or looping the partition forever.
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, OrderPlacedEvent> orderEventListenerContainerFactory(
            ConsumerFactory<String, OrderPlacedEvent> orderEventConsumerFactory,
            DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, OrderPlacedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(orderEventConsumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }

    // ---- Day 31: retry-with-backoff + dead-letter topic (DLT) --------------------------------------------
    // Until now a failure in the listener was handled by making the listener itself SWALLOW it (business
    // outcomes) or let it escape to the container's default handler (which, after retries, just logs and
    // drops the record). Neither is production-grade for a genuine, repeatable ("poison") failure: a swallow
    // hides the loss, and an escape can loop the partition. Day 31 puts a proper policy in ONE place:
    //   1. retry the record a bounded number of times, waiting `retry-backoff-ms` between attempts, then
    //   2. hand the still-failing record to the DeadLetterPublishingRecoverer, which republishes it to
    //      "<original-topic>.DLT" (default naming, same partition) so it is parked for inspection/replay —
    //      never lost, never blocking the healthy traffic behind it.
    // Both retry count and backoff are externalized (inventory.events.retry-*) so they retune per environment
    // without a recompile, exactly like every other knob (Day 7/23's rule).
    //
    // IMPORTANT — this handles TECHNICAL/unexpected failures only. Business outcomes (insufficient stock,
    // unknown SKU) are EXPECTED results the listener still catches and turns into a recorded/emitted fact;
    // they are never thrown, so they never reach this handler and never land on the DLT.

    // A dedicated producer used ONLY by the recoverer to write to the DLT: String key (the order id) + JSON
    // value (the decoded event), matching what travels on the source topic. Kept separate from the result
    // publisher's template so its generics stay unambiguous. MAX_BLOCK_MS is capped so a down broker can't
    // wedge the consumer thread while forwarding to the DLT.
    @Bean
    public KafkaTemplate<Object, Object> deadLetterKafkaTemplate() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        config.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    }

    // The shared error handler for every @KafkaListener in this service. FixedBackOff(interval, maxRetries)
    // means: initial delivery + `retryAttempts` more attempts, `retryBackoffMs` apart; when they are all
    // exhausted the recoverer sends the record to <topic>.DLT and the offset moves on.
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> deadLetterKafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(deadLetterKafkaTemplate);
        FixedBackOff backOff = new FixedBackOff(properties.retryBackoffMs(), properties.retryAttempts());
        return new DefaultErrorHandler(recoverer, backOff);
    }

    // ---- Day 28: the COMPENSATION consumer — order-cancelled -> OrderCancelledEvent -----------------------
    // The saga's compensating fact arrives on its own topic; inventory-service reacts by releasing the stock
    // it reserved. Same shared consumer config as above — same broker, same group (inventory-service's own),
    // same earliest-offset + container-managed commits — just typed to a different event. KEY = String (the
    // order id order-service keyed by); VALUE = OrderCancelledEvent, rebuilt from the type-header-less JSON.
    @Bean
    public ConsumerFactory<String, OrderCancelledEvent> orderCancelledConsumerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.consumerGroupId());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        JsonDeserializer<OrderCancelledEvent> valueDeserializer = new JsonDeserializer<>(OrderCancelledEvent.class);
        valueDeserializer.setUseTypeHeaders(false);
        valueDeserializer.addTrustedPackages("dev.dev48v.*");

        return new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(), valueDeserializer);
    }

    // The container factory OrderCancelledListener references by name. Typed to OrderCancelledEvent so the
    // listener method takes the decoded event directly. Day 31 — same retry+DLT policy as the OrderPlaced
    // factory: a compensation record that keeps failing is retried then parked on order-cancelled.DLT.
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, OrderCancelledEvent> orderCancelledListenerContainerFactory(
            ConsumerFactory<String, OrderCancelledEvent> orderCancelledConsumerFactory,
            DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, OrderCancelledEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(orderCancelledConsumerFactory);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        return factory;
    }
}
