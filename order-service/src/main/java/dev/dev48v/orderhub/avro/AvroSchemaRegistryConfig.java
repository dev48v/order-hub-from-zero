package dev.dev48v.orderhub.avro;

import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

import java.util.HashMap;
import java.util.Map;

// Day 33 — SCHEMA REGISTRY + AVRO. The whole Kafka story so far has moved a JSON blob on the wire (Day 25's
// JsonSerializer, Day 26's JsonDeserializer). JSON is a fine teaching default but a WEAK contract: nothing
// stops a producer renaming a field or changing a type, and the break only surfaces at runtime, in a consumer,
// in production. Avro + a schema registry turn the event into a CHECKED, VERSIONED contract:
//
//   • The event's shape is declared once in OrderPlaced.avsc and compiled to a SpecificRecord (dev.dev48v.
//     orderhub.avro.OrderPlaced) by the avro-maven-plugin. Both sides bind to that generated type.
//   • The KafkaAvroSerializer registers the writer schema in the registry (getting back a small schema id) and
//     puts only that id + the compact binary Avro on the wire — not the field names, on every message.
//   • The KafkaAvroDeserializer reads the id, fetches the writer schema from the registry, and decodes against
//     it (here into the SpecificRecord, specific.avro.reader=true).
//   • Before a NEW version can register, the registry runs a COMPATIBILITY check (default BACKWARD): a change
//     that a consumer couldn't survive — e.g. adding a required field with no default — is REJECTED at publish
//     time, not discovered as a 500 downstream. Adding an OPTIONAL field WITH a default is backward-compatible
//     and sails through; old data reads back with the default filled in. (See AvroSchemaEvolutionTest.)
//
// This config is the producer/consumer WIRING for that path. It is @ConditionalOnProperty(orderhub.avro.enabled
// =true) and default OFF, so a normal boot creates NONE of these beans and the Day-25 JSON producer is entirely
// untouched — every prior test stays green. Flip the flag on to publish/consume OrderPlaced as registry-backed
// Avro instead.
//
// The serializer configs are set as plain STRING keys ("schema.registry.url", "auto.register.schemas",
// "specific.avro.reader") — the canonical Confluent property names — so the only hard compile-time dependency
// on the Confluent library is the two serializer CLASSES referenced below.
@Configuration
@EnableKafka
@ConditionalOnProperty(prefix = "orderhub.avro", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(AvroEventProperties.class)
public class AvroSchemaRegistryConfig {

    // Confluent property keys (kept as literals to avoid a compile-time dependency on the config-constant
    // classes; these are the exact, stable names the Confluent serializers read).
    static final String SCHEMA_REGISTRY_URL = "schema.registry.url";
    static final String AUTO_REGISTER_SCHEMAS = "auto.register.schemas";
    static final String SPECIFIC_AVRO_READER = "specific.avro.reader";

    // WHERE the broker is — externalized exactly like the JSON producer (Day 7/23's never-hardcode rule):
    // resolves from spring.kafka.bootstrap-servers / KAFKA_BOOTSTRAP_SERVERS, local default as a safety net.
    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    private final AvroEventProperties properties;

    public AvroSchemaRegistryConfig(AvroEventProperties properties) {
        this.properties = properties;
    }

    // ---- PRODUCER side --------------------------------------------------------------------------------------
    // KEY = String (the order id, so all events for one order stay ordered on one partition); VALUE =
    // OrderPlaced (the generated Avro SpecificRecord), serialized by the KafkaAvroSerializer which registers
    // the schema in the registry and writes id + binary Avro.
    @Bean
    public ProducerFactory<String, dev.dev48v.orderhub.avro.OrderPlaced> avroOrderEventProducerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        // The registry the serializer registers/looks up schemas against. "mock://…" in tests => in-JVM
        // MockSchemaRegistryClient (no external registry); an http:// URL in every real environment.
        config.put(SCHEMA_REGISTRY_URL, properties.schemaRegistryUrl());
        // Auto-register the writer schema on first publish (dev-friendly). In strict prod you register schemas
        // out-of-band via CI and set this false so an unregistered/incompatible schema can't sneak in at runtime.
        config.put(AUTO_REGISTER_SCHEMAS, true);
        // Same durability + fail-fast posture as the JSON producer so a down broker/registry can't freeze a caller.
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3000);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, dev.dev48v.orderhub.avro.OrderPlaced> avroOrderEventKafkaTemplate(
            ProducerFactory<String, dev.dev48v.orderhub.avro.OrderPlaced> avroOrderEventProducerFactory) {
        return new KafkaTemplate<>(avroOrderEventProducerFactory);
    }

    // ---- CONSUMER side --------------------------------------------------------------------------------------
    // The KafkaAvroDeserializer reads the schema id off the record, fetches the writer schema from the registry
    // and decodes the binary Avro. specific.avro.reader=true makes it decode into the generated SpecificRecord
    // (OrderPlaced) rather than a GenericRecord, so listeners take a typed event.
    @Bean
    public ConsumerFactory<String, dev.dev48v.orderhub.avro.OrderPlaced> avroOrderEventConsumerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.consumerGroupId());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
        config.put(SCHEMA_REGISTRY_URL, properties.schemaRegistryUrl());
        config.put(SPECIFIC_AVRO_READER, true);
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, dev.dev48v.orderhub.avro.OrderPlaced>
            avroOrderEventListenerContainerFactory(
                    ConsumerFactory<String, dev.dev48v.orderhub.avro.OrderPlaced> avroOrderEventConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, dev.dev48v.orderhub.avro.OrderPlaced> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(avroOrderEventConsumerFactory);
        return factory;
    }
}
