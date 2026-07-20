package dev.dev48v.orderhub.config;

import dev.dev48v.orderhub.events.OrderPlacedEvent;
import dev.dev48v.orderhub.orchestration.OrchestrationProperties;
import dev.dev48v.orderhub.orchestration.SagaCommand;
import dev.dev48v.orderhub.orchestration.SagaReply;
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

// Day 29 — ALL the Kafka wiring for the ORCHESTRATION saga in order-service, kept SEPARATE from Day 28's
// SagaKafkaConfig so the two coordination styles stay cleanly independent. The orchestrator plays a different
// role than the choreography saga: it CONSUMES OrderPlaced (the trigger) and the saga-replies stream, and it
// PRODUCES commands onto saga-commands. So this config sets up, mirroring the earlier Kafka configs:
//   • a consumer + container factory for OrderPlacedEvent  (the trigger listener)
//   • a consumer + container factory for SagaReply         (the reply listener)
//   • a producer factory + KafkaTemplate<String, SagaCommand> (the command publisher)
//
// Every bean name is distinct from SagaKafkaConfig's, and the KafkaTemplate is typed to SagaCommand, so there
// is no collision with the choreography producers' templates (OrderShippedEvent / OrderCancelledEvent). Bean
// creation never touches a broker; the listener containers only connect when they START — and their autoStartup
// is bound to orderhub.orchestration.enabled (default false), so in the shipped/choreography configuration
// these containers never start and this config is inert.
@Configuration
@EnableKafka
@EnableConfigurationProperties(OrchestrationProperties.class)
public class OrchestrationKafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    private final OrchestrationProperties properties;

    public OrchestrationKafkaConfig(OrchestrationProperties properties) {
        this.properties = properties;
    }

    // ---- shared consumer config: both of the orchestrator's subscriptions share its OWN group ----
    private Map<String, Object> baseConsumerConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.consumerGroupId());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return config;
    }

    // A JSON value deserializer for a concrete type — the participants stamp NO type headers, so we tell it the
    // target type up front and trust our own packages (never blindly "*").
    private <T> JsonDeserializer<T> jsonDeserializer(Class<T> type) {
        JsonDeserializer<T> deserializer = new JsonDeserializer<>(type);
        deserializer.setUseTypeHeaders(false);
        deserializer.addTrustedPackages("dev.dev48v.*");
        return deserializer;
    }

    // ---- trigger: order-placed -> OrderPlacedEvent -----------------------------------------------------
    @Bean
    public ConsumerFactory<String, OrderPlacedEvent> orchestrationOrderPlacedConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(baseConsumerConfig(),
                new StringDeserializer(), jsonDeserializer(OrderPlacedEvent.class));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, OrderPlacedEvent> orchestrationOrderPlacedListenerContainerFactory(
            ConsumerFactory<String, OrderPlacedEvent> orchestrationOrderPlacedConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, OrderPlacedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(orchestrationOrderPlacedConsumerFactory);
        return factory;
    }

    // ---- replies: saga-replies -> SagaReply ------------------------------------------------------------
    @Bean
    public ConsumerFactory<String, SagaReply> sagaReplyConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(baseConsumerConfig(),
                new StringDeserializer(), jsonDeserializer(SagaReply.class));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, SagaReply> sagaReplyListenerContainerFactory(
            ConsumerFactory<String, SagaReply> sagaReplyConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, SagaReply> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(sagaReplyConsumerFactory);
        return factory;
    }

    // ---- commands: SagaCommand -> saga-commands --------------------------------------------------------
    @Bean
    public ProducerFactory<String, SagaCommand> sagaCommandProducerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        // NON-BLOCKING SAFETY: cap how long a send may BLOCK so a DOWN broker can't freeze the thread that
        // issues the next command from inside the reply handler. 2s fails fast; the publisher swallows it.
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3000);
        config.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, SagaCommand> sagaCommandKafkaTemplate(
            ProducerFactory<String, SagaCommand> sagaCommandProducerFactory) {
        return new KafkaTemplate<>(sagaCommandProducerFactory);
    }
}
