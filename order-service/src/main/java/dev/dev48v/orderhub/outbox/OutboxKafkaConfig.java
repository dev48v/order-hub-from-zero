package dev.dev48v.orderhub.outbox;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.HashMap;
import java.util.Map;

// Day 30 — the Kafka + scheduling wiring for the transactional outbox, kept in its own config alongside the
// rest of the outbox package (mirrors how Day 28/29 each carried their own SagaKafkaConfig /
// OrchestrationKafkaConfig). Two responsibilities:
//
//   • @EnableScheduling turns on Spring's scheduler so OutboxRelay's @Scheduled poll actually fires. It's
//     harmless everywhere else: the relay's method checks orderhub.outbox.enabled/relay-enabled first and
//     returns immediately when the outbox is off (the default), so the scheduler simply ticks over a no-op.
//   • the KafkaTemplate<String, String> below is the relay's publish channel. It's typed to String VALUE (not
//     OrderPlacedEvent) on purpose: the relay re-sends the payload bytes STORED on the outbox row verbatim, so
//     a StringSerializer that ships the JSON as-is is exactly right — no re-serialization, byte-for-byte
//     redelivery. Being a distinct generic type (<String,String>) it never collides with Day 25's
//     KafkaTemplate<String,OrderPlacedEvent> or Day 28's terminal-event templates.
//
// @EnableConfigurationProperties binds orderhub.outbox.* onto OutboxProperties for the writer + relay.
@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxKafkaConfig {

    // WHERE the broker is — externalized exactly like every other producer/consumer config (never hardcoded):
    // application.yml sets spring.kafka.bootstrap-servers to ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}.
    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    // KEY = String (the order id, so all events for one order stay on one partition, in order).
    // VALUE = String (the recorded JSON payload, sent verbatim). acks=all for the strongest durability, plus the
    // same short block/timeout caps the other producers use so a DOWN broker fails the send fast — the relay
    // catches that, leaves the row unsent, and retries on the next poll (nothing is lost).
    @Bean
    public ProducerFactory<String, String> outboxProducerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3000);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, String> outboxKafkaTemplate(
            ProducerFactory<String, String> outboxProducerFactory) {
        return new KafkaTemplate<>(outboxProducerFactory);
    }
}
