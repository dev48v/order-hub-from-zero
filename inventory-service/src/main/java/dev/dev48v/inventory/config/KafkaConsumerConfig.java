package dev.dev48v.inventory.config;

import dev.dev48v.inventory.events.InventoryEventProperties;
import dev.dev48v.inventory.events.OrderPlacedEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

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
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, OrderPlacedEvent> orderEventListenerContainerFactory(
            ConsumerFactory<String, OrderPlacedEvent> orderEventConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, OrderPlacedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(orderEventConsumerFactory);
        return factory;
    }
}
