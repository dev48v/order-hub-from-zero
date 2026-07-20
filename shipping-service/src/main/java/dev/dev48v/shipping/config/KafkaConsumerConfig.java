package dev.dev48v.shipping.config;

import dev.dev48v.shipping.events.PaymentProcessedEvent;
import dev.dev48v.shipping.events.ShippingEventProperties;
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

// Day 28 — the CONSUMER side of shipping-service, a near-copy of payment-service's KafkaConsumerConfig
// (Day 27). shipping-service subscribes to the payment-events topic that payment-service publishes to, in its
// OWN consumer group — so it gets its own independent copy of every PaymentProcessed. A consumer needs three
// things: WHERE the broker is, HOW to turn the bytes back into an object, and a listener-container factory
// that runs the poll loop and hands each record to the @KafkaListener method.
//
// @EnableKafka activates @KafkaListener processing. Spring Boot's Kafka auto-config normally turns it on, but
// this project's SLICED tests load an explicit set of @Configuration classes (no auto-config), so declaring it
// here guarantees the listener is detected in BOTH the full app and the test (a harmless no-op when auto-config
// is also present). @EnableConfigurationProperties binds shipping.events.* onto ShippingEventProperties so
// those settings are injectable here, in the listener and in the publisher.
@Configuration
@EnableKafka
@EnableConfigurationProperties(ShippingEventProperties.class)
public class KafkaConsumerConfig {

    // WHERE the broker is — externalized exactly like every other service (Day 7/23's never-hardcode rule):
    // application.yml sets spring.kafka.bootstrap-servers to ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}, so the
    // SAME jar reads the address from an env var in every environment and falls back to the local
    // docker-compose broker in dev. The default here is a second safety net.
    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    private final ShippingEventProperties properties;

    public KafkaConsumerConfig(ShippingEventProperties properties) {
        this.properties = properties;
    }

    // The ConsumerFactory holds the shared consumer configuration. KEY = String (the order id payment-service
    // keyed by); VALUE = PaymentProcessedEvent, rebuilt from the JSON on the topic.
    @Bean
    public ConsumerFactory<String, PaymentProcessedEvent> paymentEventConsumerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // The consumer GROUP — shipping-service's OWN group, DISTINCT from inventory-service's and
        // payment-service's. That is what makes shipping-service receive its own copy of every PaymentProcessed.
        config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.consumerGroupId());
        // On a brand-new group with no committed offset, start at the EARLIEST record so we don't miss events
        // produced before this consumer first connected. Once the group has committed offsets it resumes there.
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Let Spring's container own offset commits (commit AFTER a record is processed) rather than the client
        // auto-committing on a timer — offsets then track what we've actually handled.
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        // VALUE deserializer: JSON bytes -> PaymentProcessedEvent. payment-service stamps NO Java type headers
        // (ADD_TYPE_INFO_HEADERS=false to keep the payload language-neutral), so we tell the deserializer the
        // concrete target type up front and to IGNORE type headers. addTrustedPackages is the allow-list for
        // which classes JSON may be deserialized into (never blindly "*" a hostile topic; here we trust our own
        // packages). The producer keys by order id as a plain String.
        JsonDeserializer<PaymentProcessedEvent> valueDeserializer =
                new JsonDeserializer<>(PaymentProcessedEvent.class);
        valueDeserializer.setUseTypeHeaders(false);
        valueDeserializer.addTrustedPackages("dev.dev48v.*");

        return new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(), valueDeserializer);
    }

    // The listener-container factory the @KafkaListener references by name. It runs the poll loop, hands each
    // record to onPaymentProcessed, and commits offsets after successful processing. Typed to
    // PaymentProcessedEvent so the listener method takes the event directly with no manual parsing.
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PaymentProcessedEvent> paymentEventListenerContainerFactory(
            ConsumerFactory<String, PaymentProcessedEvent> paymentEventConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, PaymentProcessedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(paymentEventConsumerFactory);
        return factory;
    }
}
