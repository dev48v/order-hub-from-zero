package dev.dev48v.inventory.events;

import dev.dev48v.inventory.config.KafkaConsumerConfig;
import dev.dev48v.inventory.stock.InMemoryStockRepository;
import dev.dev48v.inventory.stock.InventoryService;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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

// Day 26 — proves the consumer end to end WITHOUT a real, external broker: @EmbeddedKafka stands up a
// throwaway in-JVM Kafka broker for this test class, so the build needs no Docker and no running Kafka.
// We build a tiny SLICE of the app — the REAL consumer beans (KafkaConsumerConfig + OrderPlacedListener)
// wired to a real InventoryService over the in-memory stock repository + the reservation ledger — then
// PUBLISH an OrderPlaced (as JSON, exactly like order-service's producer) and assert inventory-service
// consumes it and DECREMENTS the available stock.
//
// Why a sliced context (explicit classes) rather than the whole app: it keeps the test about Kafka only —
// no web server, no Eureka, no config server — while still exercising the genuine consume path
// (deserialize -> idempotency -> reserve -> record). @EnableKafka lives on KafkaConsumerConfig, so the
// @KafkaListener is detected even in this sliced (no auto-config) context. The @Value broker address in
// KafkaConsumerConfig resolves to the embedded broker via the property below, so the same code that runs
// in production runs here.
@SpringBootTest(
        classes = OrderPlacedConsumerTest.TestApp.class,
        properties = {
                "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
                "inventory.events.enabled=true",
                "inventory.events.order-placed-topic=order-placed",
                "inventory.events.consumer-group-id=inventory-consumer-test"
        })
@EmbeddedKafka(partitions = 1, topics = "order-placed")
@DisplayName("Day 26 · inventory-service consumes OrderPlaced and reserves stock")
class OrderPlacedConsumerTest {

    private static final String TOPIC = "order-placed";

    // The real consumer beans + a real InventoryService over the seeded in-memory repository + the ledger.
    // Same shape as the producer test's TestApp: a plain (lite) config passed as the sole classes = ... so
    // Spring Boot keeps the context SLICED and does not fall back to the full @SpringBootApplication.
    @Import({KafkaConsumerConfig.class, OrderPlacedListener.class, InventoryService.class,
            InMemoryStockRepository.class, ReservationLedger.class})
    static class TestApp {
    }

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private ReservationLedger ledger;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    // A JSON producer that mirrors order-service's real producer (JSON value, NO type headers), so the
    // bytes on the topic are exactly what the consumer decodes in production.
    private KafkaTemplate<String, OrderPlacedEvent> producer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);   // language-neutral payload, like the real producer
        ProducerFactory<String, OrderPlacedEvent> pf = new DefaultKafkaProducerFactory<>(props);
        return new KafkaTemplate<>(pf);
    }

    private OrderPlacedEvent event(String orderId, String item, int quantity) {
        return new OrderPlacedEvent(UUID.randomUUID().toString(), orderId, "Ada", item, quantity,
                "PLACED", Instant.now(), Instant.now());
    }

    @Test
    @DisplayName("an OrderPlaced event reserves stock (available quantity is decremented)")
    void consumingOrderPlacedReservesStock() {
        int before = inventoryService.getStock("KEYBOARD-001").available();   // seeded 42
        KafkaTemplate<String, OrderPlacedEvent> producer = producer();

        producer.send(TOPIC, "ORD-K", event("ORD-K", "KEYBOARD-001", 2));

        // The listener runs asynchronously; wait for the reservation to land.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(inventoryService.getStock("KEYBOARD-001").available()).isEqualTo(before - 2));

        assertThat(ledger.forOrder("ORD-K")).isPresent()
                .get().extracting(Reservation::outcome).isEqualTo("RESERVED");
    }

    @Test
    @DisplayName("a duplicate OrderPlaced (same order id) does NOT double-reserve - idempotent")
    void duplicateOrderPlacedIsNotDoubleReserved() {
        int before = inventoryService.getStock("MOUSE-001").available();      // seeded 30
        KafkaTemplate<String, OrderPlacedEvent> producer = producer();

        OrderPlacedEvent dup = event("ORD-M", "MOUSE-001", 3);
        producer.send(TOPIC, "ORD-M", dup);
        producer.send(TOPIC, "ORD-M", dup);   // AT-LEAST-ONCE redelivery of the SAME event
        // A sentinel on a different order. With a single partition, records are consumed in send order,
        // so once the sentinel is processed we KNOW both duplicate deliveries were already handled.
        producer.send(TOPIC, "ORD-SENTINEL-1", event("ORD-SENTINEL-1", "HUB-001", 1));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(ledger.forOrder("ORD-SENTINEL-1")).isPresent());

        // Reserved EXACTLY ONCE despite two deliveries — 30 - 3 = 27, not 24.
        assertThat(inventoryService.getStock("MOUSE-001").available()).isEqualTo(before - 3);
        assertThat(ledger.forOrder("ORD-M")).isPresent()
                .get().extracting(Reservation::outcome).isEqualTo("RESERVED");
    }

    @Test
    @DisplayName("insufficient stock is handled gracefully - the listener survives and keeps consuming")
    void insufficientStockIsHandledGracefully() {
        KafkaTemplate<String, OrderPlacedEvent> producer = producer();

        producer.send(TOPIC, "ORD-OOS", event("ORD-OOS", "STAND-001", 1));   // STAND-001 seeded 0
        // A sentinel AFTER the bad event: if the listener had crashed/looped on the poison record, this
        // would never be processed. Its arrival proves the listener recovered and moved on.
        producer.send(TOPIC, "ORD-SENTINEL-2", event("ORD-SENTINEL-2", "HUB-001", 1));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(ledger.forOrder("ORD-SENTINEL-2")).isPresent());

        assertThat(ledger.forOrder("ORD-OOS")).isPresent()
                .get().extracting(Reservation::outcome).isEqualTo("INSUFFICIENT_STOCK");
        // Nothing was reserved — the out-of-stock SKU is untouched.
        assertThat(inventoryService.getStock("STAND-001").available()).isEqualTo(0);
    }
}
