package dev.dev48v.payment.config;

import dev.dev48v.payment.orchestration.OrchestrationProperties;
import dev.dev48v.payment.orchestration.SagaCommand;
import dev.dev48v.payment.orchestration.SagaReply;
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

// Day 29 — the Kafka wiring for payment-service's ORCHESTRATION command handler, added beside the Day-27
// choreography configs. payment-service CONSUMES SagaCommand from saga-commands and PRODUCES SagaReply onto
// saga-replies. Bean names are distinct from the choreography configs and the template is typed to SagaReply,
// so there is no collision with the existing PaymentProcessed producer. The handler's autoStartup is gated by
// payment.orchestration.enabled (default false), so these containers stay dormant unless orchestration is on.
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

    @Bean
    public ConsumerFactory<String, SagaCommand> sagaCommandConsumerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.consumerGroupId());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        JsonDeserializer<SagaCommand> valueDeserializer = new JsonDeserializer<>(SagaCommand.class);
        valueDeserializer.setUseTypeHeaders(false);
        valueDeserializer.addTrustedPackages("dev.dev48v.*");
        return new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(), valueDeserializer);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, SagaCommand> sagaCommandListenerContainerFactory(
            ConsumerFactory<String, SagaCommand> sagaCommandConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, SagaCommand> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(sagaCommandConsumerFactory);
        return factory;
    }

    @Bean
    public ProducerFactory<String, SagaReply> sagaReplyProducerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3000);
        config.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, SagaReply> sagaReplyKafkaTemplate(
            ProducerFactory<String, SagaReply> sagaReplyProducerFactory) {
        return new KafkaTemplate<>(sagaReplyProducerFactory);
    }
}
