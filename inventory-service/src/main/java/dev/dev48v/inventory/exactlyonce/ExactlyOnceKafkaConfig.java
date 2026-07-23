package dev.dev48v.inventory.exactlyonce;

import dev.dev48v.inventory.events.OrderPlacedEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

// Day 32 — the consumer wiring for the EXACTLY-ONCE listener. It is separate from Day 26's KafkaConsumerConfig
// because it needs a DIFFERENT container contract: manual offset commits so the app — not a background timer —
// decides exactly when an offset advances, and it does so only after the DB transaction has committed.
//
// The three settings that define the pattern at the Kafka layer:
//
//   • enable.auto.commit = false — the client must NOT auto-commit offsets on a timer. If it did, an offset
//     could advance before (or independent of) our DB work, breaking the "commit DB, then ack" ordering.
//   • ackMode = MANUAL_IMMEDIATE — the container hands the listener an Acknowledgment and commits the offset
//     only when the listener calls ack.acknowledge(). MANUAL_IMMEDIATE commits right away (vs MANUAL, which
//     queues the commit for the next poll), keeping the committed offset tight to what we have durably stored.
//   • isolation.level = read_committed — on the READ side, only ever see records from producer transactions
//     that have COMMITTED (never aborted/in-flight ones). This is the consumer half of Kafka's transactional
//     exactly-once: paired with an idempotent/transactional producer upstream, the consumer is fed a clean,
//     committed-only stream, and our dedup+tx then extends that guarantee across the boundary into our DB.
//
// These beans always exist (like the Day-26 factories); the listener that USES this factory only starts when
// orderhub.exactly-once.enabled is true (its autoStartup is bound to that flag), so the config is inert when
// the feature is off.
@Configuration
@EnableConfigurationProperties(ExactlyOnceProperties.class)
public class ExactlyOnceKafkaConfig {

    // Broker address — externalized exactly like every other Kafka knob (resolves from
    // spring.kafka.bootstrap-servers / KAFKA_BOOTSTRAP_SERVERS, local default as a safety net).
    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    private final ExactlyOnceProperties properties;

    public ExactlyOnceKafkaConfig(ExactlyOnceProperties properties) {
        this.properties = properties;
    }

    // The consumer configuration for the exactly-once group. KEY = String (the order id the producer keyed
    // by); VALUE = OrderPlacedEvent, rebuilt from the type-header-less JSON on the topic (same contract the
    // Day-26 consumer decodes).
    @Bean
    public ConsumerFactory<String, OrderPlacedEvent> exactlyOnceConsumerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // This path's OWN consumer group, distinct from the Day-26 group, so the two never split partitions
        // between them if both happen to be enabled at once.
        config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.getConsumerGroupId());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        // Offsets are committed by US (manually, after the DB commit), never by a client-side timer.
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // Only ever read records from COMMITTED producer transactions — the consumer half of Kafka's
        // transactional exactly-once (see the class comment).
        config.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

        JsonDeserializer<OrderPlacedEvent> valueDeserializer = new JsonDeserializer<>(OrderPlacedEvent.class);
        valueDeserializer.setUseTypeHeaders(false);
        valueDeserializer.addTrustedPackages("dev.dev48v.*");

        return new DefaultKafkaConsumerFactory<>(config, new StringDeserializer(), valueDeserializer);
    }

    // The container factory the exactly-once @KafkaListener references by name. The decisive line is the
    // ackMode: MANUAL_IMMEDIATE, which is what lets the listener commit offsets by hand AFTER its DB
    // transaction commits, turning Kafka's at-least-once delivery into effectively-once processing.
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, OrderPlacedEvent> exactlyOnceListenerContainerFactory(
            ConsumerFactory<String, OrderPlacedEvent> exactlyOnceConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, OrderPlacedEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(exactlyOnceConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
