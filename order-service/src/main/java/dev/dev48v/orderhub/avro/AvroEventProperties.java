package dev.dev48v.orderhub.avro;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Day 33 — the switches for the AVRO + SCHEMA-REGISTRY event path, bound type-safely onto an immutable record
// with @ConfigurationProperties(prefix = "orderhub.avro") — the same pattern as OrderEventProperties (Day 25)
// and ExactlyOnceProperties (Day 32). Kept as its OWN opt-in prefix so it layers cleanly on top of the shipped
// JSON producer (Day 25) instead of replacing it.
//
//   • enabled            — master switch. DEFAULT FALSE, so a normal boot behaves exactly like Day 25/32: the
//                          JSON KafkaTemplate is the producer and NONE of the Avro/registry beans are created
//                          (AvroSchemaRegistryConfig is @ConditionalOnProperty on this flag). Flip it true to
//                          publish/consume the OrderPlaced event as Avro against a Confluent-style registry.
//   • orderPlacedTopic   — the topic the Avro OrderPlaced is produced to. Deliberately DISTINCT from the JSON
//                          "order-placed" topic so the two encodings never land on the same topic (a JSON
//                          consumer must never be handed Avro bytes).
//   • schemaRegistryUrl  — WHERE the schema registry lives. In every real environment this is an http:// URL
//                          (e.g. http://localhost:8081); the @EmbeddedKafka test sets it to "mock://…", which
//                          makes the Confluent serializers use an in-JVM MockSchemaRegistryClient so the test
//                          needs NO external registry running. Externalized per Day 7/23's never-hardcode rule.
//   • consumerGroupId    — this path's OWN Kafka consumer group, distinct from every other consumer group.
//
// @DefaultValue gives each field a safe fallback so the record always constructs even if the keys are absent.
// Registered via @EnableConfigurationProperties(AvroEventProperties.class) on AvroSchemaRegistryConfig.
@ConfigurationProperties(prefix = "orderhub.avro")
public record AvroEventProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("order-placed-avro") String orderPlacedTopic,
        @DefaultValue("mock://orderhub") String schemaRegistryUrl,
        @DefaultValue("order-avro") String consumerGroupId
) {}
