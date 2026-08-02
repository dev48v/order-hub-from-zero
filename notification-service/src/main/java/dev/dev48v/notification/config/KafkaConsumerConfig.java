package dev.dev48v.notification.config;

import dev.dev48v.notification.events.OrderPlacedEvent;
import dev.dev48v.notification.events.PaymentProcessedEvent;
import dev.dev48v.notification.events.ShipmentScheduledEvent;
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

// Day 41 — the CONSUMER wiring for notification-service, in the same shape as payment-service's (Day 27) and
// shipping-service's (Day 28) KafkaConsumerConfig, but for THREE topics instead of one: this service subscribes
// to order-placed, payment-events AND shipping-events, each with a different value type. A consumer needs three
// things per stream: WHERE the broker is, HOW to turn the bytes back into the right object, and a
// listener-container factory that runs the poll loop and hands each record to the matching @KafkaListener method.
//
// @EnableKafka activates @KafkaListener processing. Boot's Kafka auto-config normally turns it on, but the
// SLICED @EmbeddedKafka test loads an explicit set of @Configuration classes (no auto-config), so declaring it
// here guarantees the listeners are detected in BOTH the full app and the test (a harmless no-op when
// auto-config is also present). @EnableConfigurationProperties binds orderhub.notifications.* onto
// NotificationProperties so those settings are injectable here and in the dispatcher.
@Configuration
@EnableKafka
@EnableConfigurationProperties(NotificationProperties.class)
public class KafkaConsumerConfig {

    // WHERE the broker is — externalized exactly like every other service (never hardcoded): application.yml
    // sets spring.kafka.bootstrap-servers to ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}, so the SAME jar reads
    // the address from an env var in every environment and falls back to the local docker-compose broker in dev.
    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    private final NotificationProperties properties;

    public KafkaConsumerConfig(NotificationProperties properties) {
        this.properties = properties;
    }

    // A generic JSON ConsumerFactory builder — one place for the shared consumer settings, parameterised by the
    // concrete event type each topic carries. KEY = String (the order id the producers key by); VALUE = the
    // given event type, rebuilt from the JSON on the topic. The three topics all belong to notification-service's
    // ONE consumer group, so it receives its own independent copy of every event.
    private <T> ConsumerFactory<String, T> jsonConsumerFactory(Class<T> valueType) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // notification-service's OWN consumer group, DISTINCT from every other service's — that is what makes it
        // receive its own copy of every event, without stealing anyone else's.
        config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.consumerGroupId());
        // Brand-new group with no committed offset -> start at the EARLIEST record so we don't miss events
        // produced before this consumer first connected. Once the group has committed offsets it resumes there.
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Let Spring's container own offset commits (commit AFTER a record is processed) rather than the client
        // auto-committing on a timer — offsets then track what we've actually notified on.
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        // VALUE deserializer: JSON bytes -> the concrete event type. The producers stamp NO Java type headers
        // (ADD_TYPE_INFO_HEADERS=false to keep the payload language-neutral), so we tell the deserializer the
        // target type up front and to IGNORE type headers. addTrustedPackages is the allow-list for which classes
        // JSON may be deserialized into (never blindly "*" a topic; here we trust our own packages).
        JsonDeserializer<T> valueDeserializer = new JsonDeserializer<>(valueType);
        valueDeserializer.setUseTypeHeaders(false);
        valueDeserializer.addTrustedPackages("dev.dev48v.*");

        return new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(), valueDeserializer);
    }

    // A generic listener-container factory builder — wires a typed ConsumerFactory to the shared error handler.
    // Each @KafkaListener references one of these by name; the factory runs the poll loop, hands each record to
    // the listener method, and commits offsets after successful processing.
    private <T> ConcurrentKafkaListenerContainerFactory<String, T> listenerContainerFactory(
            Class<T> valueType, DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, T> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(jsonConsumerFactory(valueType));
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    // ---- one container factory per consumed event type (referenced by name from NotificationEventListener) ----

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, OrderPlacedEvent> orderPlacedListenerContainerFactory(
            DefaultErrorHandler kafkaErrorHandler) {
        return listenerContainerFactory(OrderPlacedEvent.class, kafkaErrorHandler);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PaymentProcessedEvent> paymentEventListenerContainerFactory(
            DefaultErrorHandler kafkaErrorHandler) {
        return listenerContainerFactory(PaymentProcessedEvent.class, kafkaErrorHandler);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, ShipmentScheduledEvent> shipmentEventListenerContainerFactory(
            DefaultErrorHandler kafkaErrorHandler) {
        return listenerContainerFactory(ShipmentScheduledEvent.class, kafkaErrorHandler);
    }

    // ---- retry-with-backoff + dead-letter topic (DLT), shared by all three listeners ------------------------
    // The same one-place failure policy the other consumers got: retry a genuinely failing ("poison") record a
    // bounded number of times, retryBackoffMs apart, then republish it to "<topic>.DLT" so it is parked for
    // inspection/replay rather than dropped or looped. This is for TECHNICAL failures (e.g. SMTP down); a
    // notification is never a "business decline", so there is no normal-outcome branch to keep off the DLT.
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

    // FixedBackOff(interval, maxRetries): initial delivery + retryAttempts more, retryBackoffMs apart; when
    // exhausted the recoverer sends the record to <topic>.DLT and the offset moves on.
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> deadLetterKafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(deadLetterKafkaTemplate);
        FixedBackOff backOff = new FixedBackOff(properties.retryBackoffMs(), properties.retryAttempts());
        return new DefaultErrorHandler(recoverer, backOff);
    }
}
