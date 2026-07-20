package dev.dev48v.inventory.events;

import dev.dev48v.inventory.config.KafkaConsumerConfig;
import dev.dev48v.inventory.config.KafkaProducerConfig;
import dev.dev48v.inventory.stock.InMemoryStockRepository;
import dev.dev48v.inventory.stock.InventoryService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Day 28 — proves the inventory half of the CHOREOGRAPHY SAGA end to end WITHOUT a real, external broker.
// @EmbeddedKafka stands up a throwaway in-JVM Kafka broker for this class (no Docker, no running Kafka). We
// build a SLICE of inventory-service — the REAL consumer + producer beans (KafkaConsumerConfig,
// KafkaProducerConfig, OrderPlacedListener, OrderCancelledListener, StockResultPublisher) over a real
// InventoryService on the in-memory stock repository + the reservation ledger — then play the roles of
// order-service and payment-service by PUBLISHING the events that drive the saga.
//
// Two halves of the saga are proven from inventory's side, the mirror of OrderSagaChoreographyTest:
//   • ANSWER — an OrderPlaced makes inventory reserve stock AND EMIT a StockReserved(RESERVED) the order saga
//              waits on (the "stock reserved" leg of the happy path).
//   • COMPENSATE — the saga's OrderCancelled makes inventory RELEASE the stock it had held (the compensating
//                  operation that "rolls back" the reserve, since there is no shared transaction).
// Plus: the release is IDEMPOTENT (a redelivered OrderCancelled never double-replenishes), and a cancel for an
// order that was never reserved is a harmless no-op.
@SpringBootTest(
        classes = StockReleaseCompensationTest.TestApp.class,
        properties = {
                "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
                "inventory.events.enabled=true",
                "inventory.events.order-placed-topic=order-placed",
                "inventory.events.stock-events-topic=inventory-events",
                "inventory.events.order-cancelled-topic=order-cancelled",
                "inventory.events.consumer-group-id=inventory-compensation-test"
        })
@EmbeddedKafka(partitions = 1, topics = {"order-placed", "inventory-events", "order-cancelled"})
@DisplayName("Day 28 · inventory-service saga: emit StockReserved on reserve, release stock on OrderCancelled")
class StockReleaseCompensationTest {

    private static final String ORDER_PLACED = "order-placed";
    private static final String INVENTORY_EVENTS = "inventory-events";
    private static final String ORDER_CANCELLED = "order-cancelled";

    // The real consume + produce beans + a real InventoryService over the seeded in-memory repository + ledger.
    @Import({KafkaConsumerConfig.class, KafkaProducerConfig.class, OrderPlacedListener.class,
            OrderCancelledListener.class, StockResultPublisher.class, InventoryService.class,
            InMemoryStockRepository.class, ReservationLedger.class})
    static class TestApp {
    }

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private ReservationLedger ledger;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    // ---- producers that mimic order-service (JSON, no type headers) -------------------------------------
    private <T> KafkaTemplate<String, T> producer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        ProducerFactory<String, T> pf = new DefaultKafkaProducerFactory<>(props);
        return new KafkaTemplate<>(pf);
    }

    // ---- a consumer that decodes the StockReserved facts inventory emits (like the order saga would) -----
    private Consumer<String, StockReservedEvent> stockEventConsumer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "assert-stock-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        JsonDeserializer<StockReservedEvent> vd = new JsonDeserializer<>(StockReservedEvent.class);
        vd.setUseTypeHeaders(false);
        vd.addTrustedPackages("dev.dev48v.*");
        Consumer<String, StockReservedEvent> consumer =
                new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), vd).createConsumer();
        embeddedKafka.consumeFromAnEmbeddedTopic(consumer, INVENTORY_EVENTS);
        return consumer;
    }

    private StockReservedEvent awaitStockReserved(String orderId) {
        Consumer<String, StockReservedEvent> consumer = stockEventConsumer();
        try {
            List<StockReservedEvent> found = new ArrayList<>();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                ConsumerRecords<String, StockReservedEvent> recs = consumer.poll(Duration.ofMillis(300));
                recs.forEach(r -> { if (orderId.equals(r.value().orderId())) found.add(r.value()); });
                assertThat(found).isNotEmpty();
            });
            return found.get(0);
        } finally {
            consumer.close();
        }
    }

    private OrderPlacedEvent orderPlaced(String orderId, String item, int quantity) {
        return new OrderPlacedEvent(UUID.randomUUID().toString(), orderId, "Ada", item, quantity,
                "PLACED", Instant.now(), Instant.now());
    }

    private OrderCancelledEvent orderCancelled(String orderId, String sku, int quantity, String reason) {
        return new OrderCancelledEvent(UUID.randomUUID().toString(), orderId, sku, quantity, reason, Instant.now());
    }

    // Reserve `qty` of `sku` for `orderId` by publishing an OrderPlaced, and wait until it lands in the ledger.
    private void reserveAndAwait(String orderId, String sku, int qty) {
        producer().send(ORDER_PLACED, orderId, orderPlaced(orderId, sku, qty));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(ledger.forOrder(orderId)).isPresent()
                        .get().extracting(Reservation::outcome).isEqualTo("RESERVED"));
    }

    @Test
    @DisplayName("ANSWER: an OrderPlaced reserves stock AND emits a StockReserved(RESERVED) the saga waits on")
    void reserveEmitsStockReserved() {
        int before = inventoryService.getStock("KEYBOARD-001").available();  // seeded 42

        producer().send(ORDER_PLACED, "ORD-R", orderPlaced("ORD-R", "KEYBOARD-001", 2));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(inventoryService.getStock("KEYBOARD-001").available()).isEqualTo(before - 2));

        StockReservedEvent emitted = awaitStockReserved("ORD-R");
        assertThat(emitted.outcome()).isEqualTo("RESERVED");
        assertThat(emitted.sku()).isEqualTo("KEYBOARD-001");
        assertThat(emitted.quantity()).isEqualTo(2);
        assertThat(emitted.remaining()).isEqualTo(before - 2);
    }

    @Test
    @DisplayName("COMPENSATE: an OrderCancelled releases the reserved stock and marks the ledger RELEASED")
    void cancelReleasesReservedStock() {
        int before = inventoryService.getStock("MOUSE-001").available();     // seeded 30
        reserveAndAwait("ORD-C", "MOUSE-001", 3);
        assertThat(inventoryService.getStock("MOUSE-001").available()).isEqualTo(before - 3);

        // The saga compensates — payment was declined, so the stock must go back on hand.
        producer().send(ORDER_CANCELLED, "ORD-C", orderCancelled("ORD-C", "MOUSE-001", 3, "PAYMENT_DECLINED"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(inventoryService.getStock("MOUSE-001").available()).isEqualTo(before));
        assertThat(ledger.forOrder("ORD-C")).isPresent()
                .get().extracting(Reservation::outcome).isEqualTo("RELEASED");
    }

    @Test
    @DisplayName("IDEMPOTENT: a redelivered OrderCancelled releases the stock only ONCE - no double-replenish")
    void redeliveredCancelReleasesOnlyOnce() {
        int hubBefore = inventoryService.getStock("HUB-001").available();     // seeded 15
        int webcamBefore = inventoryService.getStock("WEBCAM-001").available();// seeded 12
        reserveAndAwait("ORD-DUP", "HUB-001", 4);
        reserveAndAwait("ORD-SENTINEL", "WEBCAM-001", 1);

        // AT-LEAST-ONCE: the compensating event is delivered TWICE for the same order.
        OrderCancelledEvent cancel = orderCancelled("ORD-DUP", "HUB-001", 4, "PAYMENT_DECLINED");
        KafkaTemplate<String, OrderCancelledEvent> p = producer();
        p.send(ORDER_CANCELLED, "ORD-DUP", cancel);
        p.send(ORDER_CANCELLED, "ORD-DUP", cancel);
        // A sentinel cancel AFTER the duplicates. With one partition, records are consumed in send order, so
        // once the sentinel is released we KNOW both duplicate deliveries were already handled.
        p.send(ORDER_CANCELLED, "ORD-SENTINEL", orderCancelled("ORD-SENTINEL", "WEBCAM-001", 1, "PAYMENT_DECLINED"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(inventoryService.getStock("WEBCAM-001").available()).isEqualTo(webcamBefore));

        // Released EXACTLY ONCE despite two deliveries — back to 15, not 19.
        assertThat(inventoryService.getStock("HUB-001").available()).isEqualTo(hubBefore);
        assertThat(ledger.forOrder("ORD-DUP")).isPresent()
                .get().extracting(Reservation::outcome).isEqualTo("RELEASED");
    }

    @Test
    @DisplayName("NO-OP: an OrderCancelled for an order that was never reserved changes nothing and does not crash")
    void cancelWithoutReservationIsNoOp() {
        int before = inventoryService.getStock("MONITOR-4K").available();    // seeded 7
        reserveAndAwait("ORD-REAL", "MONITOR-4K", 2);

        // A cancel for an order this service never saw a reservation for — must be ignored, not applied.
        producer().send(ORDER_CANCELLED, "ORD-GHOST", orderCancelled("ORD-GHOST", "MONITOR-4K", 5, "PAYMENT_DECLINED"));
        // A sentinel cancel for the real order proves the listener survived the ghost and kept consuming.
        producer().send(ORDER_CANCELLED, "ORD-REAL", orderCancelled("ORD-REAL", "MONITOR-4K", 2, "PAYMENT_DECLINED"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(ledger.forOrder("ORD-REAL")).isPresent()
                        .get().extracting(Reservation::outcome).isEqualTo("RELEASED"));

        // The ghost released nothing: MONITOR-4K only ever moved by the real order (7 -2 reserve, +2 release = 7).
        assertThat(inventoryService.getStock("MONITOR-4K").available()).isEqualTo(before);
        assertThat(ledger.forOrder("ORD-GHOST")).isEmpty();
    }
}
