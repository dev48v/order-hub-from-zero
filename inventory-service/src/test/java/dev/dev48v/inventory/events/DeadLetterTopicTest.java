package dev.dev48v.inventory.events;

import dev.dev48v.inventory.config.KafkaConsumerConfig;
import dev.dev48v.inventory.config.KafkaProducerConfig;
import dev.dev48v.inventory.domain.StockItem;
import dev.dev48v.inventory.stock.InventoryService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Day 31 — proves the retry-with-backoff + dead-letter-topic (DLT) policy end to end WITHOUT a real broker:
// @EmbeddedKafka stands up a throwaway in-JVM Kafka for this class, so the build needs no Docker and no
// running Kafka. We wire the REAL consumer beans (KafkaConsumerConfig, with its DefaultErrorHandler +
// DeadLetterPublishingRecoverer, and OrderPlacedListener) but replace the InventoryService collaborator with
// a Mockito mock so we can script a "poison" outcome that ALWAYS throws a technical error, and a happy outcome
// that succeeds. Then we assert the two behaviours Day 31 promises:
//
//   • a poison record (one that always fails processing) is RETRIED, and once the attempts are exhausted it is
//     republished to the "order-placed.DLT" dead-letter topic — never silently dropped, never looping the
//     partition. The idempotency claim is released on each technical failure, so the retries genuinely
//     re-process (they don't hit the duplicate-skip shortcut).
//   • a happy record is processed EXACTLY ONCE and never dead-lettered.
//
// retry-attempts=2 + retry-backoff-ms=150 keep the test fast: 1 initial delivery + 2 retries = 3 attempts,
// then the record is recovered onto the DLT.
@SpringBootTest(
        classes = DeadLetterTopicTest.TestApp.class,
        properties = {
                "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
                "inventory.events.enabled=true",
                "inventory.events.order-placed-topic=order-placed",
                "inventory.events.stock-events-topic=inventory-events",
                "inventory.events.consumer-group-id=inventory-dlt-test",
                "inventory.events.retry-attempts=2",
                "inventory.events.retry-backoff-ms=150"
        })
@EmbeddedKafka(partitions = 1, topics = {"order-placed", "order-placed.DLT", "inventory-events"})
@DisplayName("Day 31 · a poison OrderPlaced is retried then routed to order-placed.DLT")
class DeadLetterTopicTest {

    private static final String TOPIC = "order-placed";
    private static final String DLT = "order-placed.DLT";
    private static final String POISON_SKU = "POISON-SKU";
    private static final String GOOD_SKU = "KEYBOARD-001";

    // The REAL consumer + error-handler beans and the REAL listener/publisher/ledger — but NOT the real
    // InventoryService (it is @MockBean'd below) so we can force a repeatable technical failure.
    @Import({KafkaConsumerConfig.class, KafkaProducerConfig.class, OrderPlacedListener.class,
            StockResultPublisher.class, ReservationLedger.class})
    static class TestApp {
    }

    @MockBean
    private InventoryService inventoryService;

    @Autowired
    private ReservationLedger ledger;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @BeforeEach
    void scriptTheCollaborator() {
        // POISON: this SKU always blows up with an unexpected (non-business) error — the kind Day 31 retries
        // and then dead-letters. A NullPointer/IllegalState from a bug or a flaky dependency would look the same.
        when(inventoryService.reserve(eq(POISON_SKU), anyInt()))
                .thenThrow(new RuntimeException("boom - poison record, processing always fails"));
        // HAPPY: this SKU reserves normally, returning the remaining stock.
        when(inventoryService.reserve(eq(GOOD_SKU), anyInt()))
                .thenReturn(new StockItem(GOOD_SKU, "Mechanical Keyboard", 40));
    }

    @Test
    @DisplayName("a poison message is retried N times then published to the .DLT topic (not dropped, not looped)")
    void poisonMessageIsRetriedThenRoutedToDlt() {
        Consumer<String, String> dltConsumer = dltConsumer();

        producer().send(TOPIC, "ORD-POISON", event("ORD-POISON", POISON_SKU, 1));

        // The still-failing record lands on the dead-letter topic, carrying its original key + JSON payload.
        ConsumerRecord<String, String> dead =
                KafkaTestUtils.getSingleRecord(dltConsumer, DLT, Duration.ofSeconds(20));
        assertThat(dead.key()).isEqualTo("ORD-POISON");
        assertThat(dead.value()).contains(POISON_SKU);

        // It was RETRIED, not dead-lettered on the first failure: initial delivery + 2 configured retries.
        verify(inventoryService, atLeast(2)).reserve(eq(POISON_SKU), anyInt());
        // Nothing was recorded for the poison order, and the claim was released (never silently "handled").
        assertThat(ledger.forOrder("ORD-POISON")).isEmpty();

        dltConsumer.close();
    }

    @Test
    @DisplayName("a happy message is processed exactly once and never dead-lettered")
    void happyMessageIsProcessedOnceAndNotDeadLettered() {
        Consumer<String, String> dltConsumer = dltConsumer();

        producer().send(TOPIC, "ORD-HAPPY", event("ORD-HAPPY", GOOD_SKU, 2));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(ledger.forOrder("ORD-HAPPY")).isPresent()
                        .get().extracting(Reservation::outcome).isEqualTo("RESERVED"));

        // Processed a single time — no retries on the happy path.
        verify(inventoryService, times(1)).reserve(eq(GOOD_SKU), anyInt());

        // No dead-letter record was produced for the happy order.
        ConsumerRecords<String, String> dlt = KafkaTestUtils.getRecords(dltConsumer, Duration.ofSeconds(2));
        dlt.forEach(record -> assertThat(record.key()).isNotEqualTo("ORD-HAPPY"));

        dltConsumer.close();
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    // A JSON producer that mirrors order-service's real producer (JSON value, NO type headers).
    private KafkaTemplate<String, OrderPlacedEvent> producer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        ProducerFactory<String, OrderPlacedEvent> pf = new DefaultKafkaProducerFactory<>(props);
        return new KafkaTemplate<>(pf);
    }

    // A String/String consumer over the DLT, seeked to the beginning, so we read whatever the recoverer wrote.
    private Consumer<String, String> dltConsumer() {
        Map<String, Object> props =
                KafkaTestUtils.consumerProps("dlt-verify-" + UUID.randomUUID(), "true", embeddedKafka);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                props, new StringDeserializer(), new StringDeserializer()).createConsumer();
        embeddedKafka.consumeFromAnEmbeddedTopic(consumer, DLT);
        return consumer;
    }

    private OrderPlacedEvent event(String orderId, String item, int quantity) {
        return new OrderPlacedEvent(UUID.randomUUID().toString(), orderId, "Ada", item, quantity,
                "PLACED", Instant.now(), Instant.now());
    }
}
