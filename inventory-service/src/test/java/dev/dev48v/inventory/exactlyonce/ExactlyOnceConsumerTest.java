package dev.dev48v.inventory.exactlyonce;

import dev.dev48v.inventory.events.OrderPlacedEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Day 32 — proves the EXACTLY-ONCE (effectively-once) consumer end to end WITHOUT a real broker or a real
// database: @EmbeddedKafka stands up a throwaway in-JVM Kafka, and the app boots on an in-memory H2 (the
// default datasource) with Flyway creating the stock_levels + processed_events tables. The feature is turned
// ON for this test (orderhub.exactly-once.enabled=true) while the Day-26 in-memory listener is turned OFF
// (inventory.events.enabled=false), so the ONLY consumer running is the DB-backed exactly-once one.
//
// Two behaviours Day 32 promises are asserted:
//   1. HAPPY PATH — a real OrderPlaced published to the embedded broker is consumed, reserves stock in the DB
//      exactly once, writes a single processed_events marker, and the offset is manually acked after commit.
//   2. REDELIVERY — the SAME record (identical topic/partition/offset) delivered a second time is recognised
//      as a duplicate and SKIPPED, so the reservation is NOT applied again; a genuinely new record still is.
//
// The redelivery case invokes the transactional processor directly with a fixed (topic, partition, offset):
// that triple IS the identity of one physical delivery, so calling it twice is a faithful, deterministic
// simulation of Kafka redelivering the same record after a crash between DB-commit and offset-commit — the
// exact window the dedup+transaction pattern exists to close. (Two producer sends can't reproduce it: they
// are two different records at two different offsets.)
@SpringBootTest(
        classes = dev.dev48v.inventory.InventoryServiceApplication.class,
        properties = {
                "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
                "eureka.client.enabled=false",
                // Day-26 in-memory listener OFF, so the exactly-once path is the only consumer.
                "inventory.events.enabled=false",
                // The Day-32 feature under test, ON.
                "orderhub.exactly-once.enabled=true",
                "orderhub.exactly-once.order-placed-topic=order-placed",
                "orderhub.exactly-once.consumer-group-id=inventory-eo-test",
                // Isolate this test's H2 instance.
                "spring.datasource.url=jdbc:h2:mem:eo-test;DB_CLOSE_DELAY=-1"
        })
@EmbeddedKafka(partitions = 1, topics = {"order-placed"})
@DisplayName("Day 32 · exactly-once consumer reserves stock once and skips redeliveries")
class ExactlyOnceConsumerTest {

    private static final String TOPIC = "order-placed";

    @Autowired
    private ExactlyOnceProcessor processor;

    @Autowired
    private StockLevelRepository stockLevels;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @Test
    @DisplayName("a real OrderPlaced is consumed and reserves stock exactly once, writing one dedup marker")
    void consumesAndReservesExactlyOnceEndToEnd() {
        int before = stockLevels.findById("KEYBOARD-001").orElseThrow().getAvailable();   // seeded 42
        long markersBefore = processedEvents.count();

        producer().send(TOPIC, "ORD-K", event("ORD-K", "KEYBOARD-001", 2));

        // The listener runs asynchronously against the embedded broker; wait for the DB effect to land.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(stockLevels.findById("KEYBOARD-001").orElseThrow().getAvailable()).isEqualTo(before - 2);
            // Exactly one new dedup marker was written by the consumer for this delivery.
            assertThat(processedEvents.count()).isEqualTo(markersBefore + 1);
        });
    }

    @Test
    @DisplayName("a redelivered record (same topic/partition/offset) is skipped - reserved exactly once")
    void redeliveredRecordIsDedupedAndReservedOnce() {
        int before = stockLevels.findById("MOUSE-001").orElseThrow().getAvailable();       // seeded 30
        OrderPlacedEvent order = event("ORD-DUP", "MOUSE-001", 3);

        // FIRST delivery of the record at a fixed offset — reserves stock and writes the marker (same tx).
        ProcessOutcome first = processor.process(TOPIC, 0, 5000L, order);
        assertThat(first).isEqualTo(ProcessOutcome.RESERVED);
        assertThat(stockLevels.findById("MOUSE-001").orElseThrow().getAvailable()).isEqualTo(before - 3);

        // REDELIVERY of the SAME record (identical coordinates) — the crash-after-commit-before-ack case.
        ProcessOutcome second = processor.process(TOPIC, 0, 5000L, order);
        assertThat(second).isEqualTo(ProcessOutcome.DUPLICATE_SKIPPED);
        // The business effect was NOT applied a second time: still before-3, never before-6.
        assertThat(stockLevels.findById("MOUSE-001").orElseThrow().getAvailable()).isEqualTo(before - 3);
        // Exactly one marker exists for that record.
        assertThat(processedEvents.existsById(new ProcessedEventKey(TOPIC, 0, 5000L))).isTrue();

        // A genuinely NEW record (a different offset) is still processed normally — the guard blocks only
        // re-processing of an already-seen delivery, never new work. Happy path unaffected.
        ProcessOutcome fresh = processor.process(TOPIC, 0, 5001L, event("ORD-NEW", "MOUSE-001", 1));
        assertThat(fresh).isEqualTo(ProcessOutcome.RESERVED);
        assertThat(stockLevels.findById("MOUSE-001").orElseThrow().getAvailable()).isEqualTo(before - 4);
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    // A JSON producer that mirrors order-service's real producer (JSON value, NO type headers), so the bytes
    // on the topic are exactly what the consumer decodes in production.
    private KafkaTemplate<String, OrderPlacedEvent> producer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        ProducerFactory<String, OrderPlacedEvent> pf = new DefaultKafkaProducerFactory<>(props);
        return new KafkaTemplate<>(pf);
    }

    private OrderPlacedEvent event(String orderId, String item, int quantity) {
        return new OrderPlacedEvent(UUID.randomUUID().toString(), orderId, "Ada", item, quantity,
                "PLACED", Instant.now(), Instant.now());
    }
}
