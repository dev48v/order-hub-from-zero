package dev.dev48v.orderhub.avro;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// Day 33 — proves the AVRO + SCHEMA-REGISTRY path end to end WITHOUT any external infrastructure: @EmbeddedKafka
// stands up a throwaway in-JVM broker, and the schema registry is the Confluent MockSchemaRegistryClient, wired
// in purely by the "mock://…" schema.registry.url (no registry process, no Docker). The Day-33 feature is turned
// ON (orderhub.avro.enabled=true) so the real AvroSchemaRegistryConfig beans are created — the same beans that
// run in production — and the test uses them directly.
//
// The flow asserted is the whole point of a schema registry: SERIALIZE → REGISTER → DESERIALIZE.
//   1. The KafkaAvroSerializer registers the OrderPlaced writer schema in the (mock) registry and puts the
//      schema id + compact binary Avro on the topic.
//   2. The KafkaAvroDeserializer reads the id, fetches that schema back from the SAME mock scope, and decodes
//      the bytes into the generated OrderPlaced SpecificRecord (specific.avro.reader=true).
//   3. The record that comes back equals the one that was sent — a genuine registry-backed round-trip.
//
// A sliced context (only AvroSchemaRegistryConfig) keeps the test about Avro/registry wiring alone — no web
// server, Postgres, Redis or discovery — while exercising the real producer/consumer factories. It's the same
// shape as the Day-25 OrderPlacedEventTest, one encoding layer up.
@SpringBootTest(
        classes = AvroSchemaRegistryIntegrationTest.TestApp.class,
        properties = {
                "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
                "orderhub.avro.enabled=true",
                "orderhub.avro.order-placed-topic=order-placed-avro",
                // mock:// => an in-JVM MockSchemaRegistryClient keyed by this scope, shared by the producer and
                // the consumer, so no external registry has to run.
                "orderhub.avro.schema-registry-url=mock://orderhub-day33-test",
                "orderhub.avro.consumer-group-id=order-avro-it"
        })
@EmbeddedKafka(partitions = 1, topics = "order-placed-avro")
@DisplayName("Day 33 · OrderPlaced round-trips through the Avro serializers + a (mock) schema registry")
class AvroSchemaRegistryIntegrationTest {

    private static final String TOPIC = "order-placed-avro";

    // The real Avro producer/consumer beans, and nothing else. AvroSchemaRegistryConfig is
    // @ConditionalOnProperty(orderhub.avro.enabled=true), which the property above satisfies, so its beans exist.
    @Import(AvroSchemaRegistryConfig.class)
    static class TestApp {
    }

    @Autowired
    private KafkaTemplate<String, OrderPlaced> avroOrderEventKafkaTemplate;

    @Autowired
    private ConsumerFactory<String, OrderPlaced> avroOrderEventConsumerFactory;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @Test
    @DisplayName("serialize → auto-register in the mock registry → deserialize back to the same SpecificRecord")
    void avroRoundTripThroughSchemaRegistry() throws Exception {
        long now = System.currentTimeMillis();
        OrderPlaced event = OrderPlaced.newBuilder()
                .setEventId("evt-1")
                .setOrderId("ORD-1")
                .setCustomer("Ada")
                .setItem("KEYBOARD-001")
                .setQuantity(2)
                .setStatus("PLACED")
                .setPlacedAt(now)
                .setOccurredAt(now)
                .build();

        // A fresh consumer group reading the Avro topic from the beginning (the factory sets earliest).
        Consumer<String, OrderPlaced> consumer =
                avroOrderEventConsumerFactory.createConsumer("day33-it", "1");
        consumer.subscribe(List.of(TOPIC));

        try {
            // PRODUCE — serialize the SpecificRecord to binary Avro, registering the schema in the mock registry.
            avroOrderEventKafkaTemplate.send(TOPIC, event.getOrderId(), event).get(15, TimeUnit.SECONDS);

            // CONSUME — the deserializer fetches the schema by id from the registry and rebuilds the record.
            ConsumerRecord<String, OrderPlaced> record =
                    KafkaTestUtils.getSingleRecord(consumer, TOPIC, Duration.ofSeconds(15));

            assertThat(record).isNotNull();
            assertThat(record.key()).isEqualTo("ORD-1");

            OrderPlaced decoded = record.value();
            assertThat(decoded).isNotNull();
            assertThat(decoded.getOrderId()).isEqualTo("ORD-1");
            assertThat(decoded.getCustomer()).isEqualTo("Ada");
            assertThat(decoded.getItem()).isEqualTo("KEYBOARD-001");
            assertThat(decoded.getQuantity()).isEqualTo(2);
            assertThat(decoded.getStatus()).isEqualTo("PLACED");
            // Byte-for-byte round-trip: what came back off the topic equals what was published.
            assertThat(decoded).isEqualTo(event);
        } finally {
            consumer.close();
        }
    }
}
