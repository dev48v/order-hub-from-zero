package dev.dev48v.orderhub.saga;

import dev.dev48v.orderhub.config.SagaKafkaConfig;
import dev.dev48v.orderhub.domain.Order;
import dev.dev48v.orderhub.domain.OrderStatus;
import dev.dev48v.orderhub.repository.InMemoryOrderRepository;
import dev.dev48v.orderhub.repository.OrderRepository;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Day 28 — proves the CHOREOGRAPHY SAGA end to end WITHOUT a real, external broker. @EmbeddedKafka stands up
// a throwaway in-JVM Kafka broker for this class (no Docker, no running Kafka). We build a SLICE of
// order-service — the REAL saga beans (SagaKafkaConfig's consumers + producers, OrderSagaListener, OrderSaga,
// SagaResultPublisher) over an in-memory OrderRepository — then play the roles of inventory-service and
// payment-service by PUBLISHING their result events (StockReserved on inventory-events, PaymentProcessed on
// payment-events), exactly the way the payment-service test plays order-service by publishing OrderPlaced.
//
// Two paths are proven, the two halves of a saga:
//   • HAPPY  — RESERVED + APPROVED → the order is confirmed + SHIPPED and an OrderShipped is emitted.
//   • COMPENSATE — a DECLINED payment (or a stock failure) → the order is CANCELLED and an OrderCancelled is
//                  emitted onto order-cancelled, the compensating event inventory-service reacts to by
//                  releasing the stock (proven on the inventory side in StockReleaseCompensationTest).
// Plus: the compensation fires WITHOUT waiting for the other leg, and the saga is idempotent (a redelivered
// result never ships or cancels twice).
@SpringBootTest(
        classes = OrderSagaChoreographyTest.TestApp.class,
        properties = {
                "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
                "orderhub.saga.enabled=true",
                "orderhub.saga.stock-events-topic=inventory-events",
                "orderhub.saga.payment-events-topic=payment-events",
                "orderhub.saga.order-shipped-topic=order-shipped",
                "orderhub.saga.order-cancelled-topic=order-cancelled",
                "orderhub.saga.consumer-group-id=order-saga-test"
        })
@EmbeddedKafka(partitions = 1,
        topics = {"inventory-events", "payment-events", "order-shipped", "order-cancelled"})
@DisplayName("Day 28 · order-service choreography saga: ship on reserved+approved, compensate on failure")
class OrderSagaChoreographyTest {

    private static final String INVENTORY_EVENTS = "inventory-events";
    private static final String PAYMENT_EVENTS = "payment-events";
    private static final String ORDER_SHIPPED = "order-shipped";
    private static final String ORDER_CANCELLED = "order-cancelled";

    // The real saga beans + a plain in-memory OrderRepository (new'd directly so its @Profile("inmemory")
    // gate doesn't apply — the saga just needs somewhere to load/save the order it acts on). Passed as the
    // sole classes = ... so Spring Boot keeps the context SLICED (no web, no JPA, no Eureka, no config server).
    @Import({SagaKafkaConfig.class, OrderSagaListener.class, OrderSaga.class, SagaResultPublisher.class})
    static class TestApp {
        @Bean
        OrderRepository orderRepository() {
            return new InMemoryOrderRepository();
        }
    }

    @Autowired
    private OrderRepository orders;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    // ---- producers that mimic inventory-service / payment-service (JSON, no type headers) ---------------
    private <T> KafkaTemplate<String, T> producer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        ProducerFactory<String, T> pf = new DefaultKafkaProducerFactory<>(props);
        return new KafkaTemplate<>(pf);
    }

    // ---- consumers that decode the terminal facts the saga emits (like a downstream service would) ------
    private <T> Consumer<String, T> consumerFor(String topic, Class<T> type) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "assert-" + topic + "-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        JsonDeserializer<T> vd = new JsonDeserializer<>(type);
        vd.setUseTypeHeaders(false);
        vd.addTrustedPackages("dev.dev48v.*");
        Consumer<String, T> consumer =
                new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), vd).createConsumer();
        embeddedKafka.consumeFromAnEmbeddedTopic(consumer, topic);
        return consumer;
    }

    private OrderShippedEvent awaitShipped(String orderId) {
        Consumer<String, OrderShippedEvent> consumer = consumerFor(ORDER_SHIPPED, OrderShippedEvent.class);
        try {
            List<OrderShippedEvent> found = new ArrayList<>();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                ConsumerRecords<String, OrderShippedEvent> recs = consumer.poll(Duration.ofMillis(300));
                recs.forEach(r -> { if (orderId.equals(r.value().orderId())) found.add(r.value()); });
                assertThat(found).isNotEmpty();
            });
            return found.get(0);
        } finally {
            consumer.close();
        }
    }

    private List<OrderCancelledEvent> drainCancelled(String orderId, Duration window) {
        Consumer<String, OrderCancelledEvent> consumer = consumerFor(ORDER_CANCELLED, OrderCancelledEvent.class);
        List<OrderCancelledEvent> out = new ArrayList<>();
        try {
            long end = System.currentTimeMillis() + window.toMillis();
            while (System.currentTimeMillis() < end) {
                ConsumerRecords<String, OrderCancelledEvent> recs = consumer.poll(Duration.ofMillis(300));
                recs.forEach(r -> { if (orderId.equals(r.value().orderId())) out.add(r.value()); });
            }
        } finally {
            consumer.close();
        }
        return out;
    }

    // Drain every OrderShipped for an order over a fixed window — used to COUNT emissions for exactly-once.
    private List<OrderShippedEvent> drainShipped(String orderId, Duration window) {
        Consumer<String, OrderShippedEvent> consumer = consumerFor(ORDER_SHIPPED, OrderShippedEvent.class);
        List<OrderShippedEvent> out = new ArrayList<>();
        try {
            long end = System.currentTimeMillis() + window.toMillis();
            while (System.currentTimeMillis() < end) {
                ConsumerRecords<String, OrderShippedEvent> recs = consumer.poll(Duration.ofMillis(300));
                recs.forEach(r -> { if (orderId.equals(r.value().orderId())) out.add(r.value()); });
            }
        } finally {
            consumer.close();
        }
        return out;
    }

    private OrderCancelledEvent awaitCancelled(String orderId) {
        Consumer<String, OrderCancelledEvent> consumer = consumerFor(ORDER_CANCELLED, OrderCancelledEvent.class);
        try {
            List<OrderCancelledEvent> found = new ArrayList<>();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                ConsumerRecords<String, OrderCancelledEvent> recs = consumer.poll(Duration.ofMillis(300));
                recs.forEach(r -> { if (orderId.equals(r.value().orderId())) found.add(r.value()); });
                assertThat(found).isNotEmpty();
            });
            return found.get(0);
        } finally {
            consumer.close();
        }
    }

    // Seed an order in the PLACED state — stands in for an order this service placed earlier.
    private Order seed(String orderId, String item, int quantity) {
        return orders.save(new Order(orderId, "Ada", item, quantity));
    }

    private StockReservedEvent reserved(String orderId, String sku, int qty, int remaining) {
        return new StockReservedEvent(UUID.randomUUID().toString(), orderId, sku, qty,
                "RESERVED", remaining, "cause-" + orderId, Instant.now());
    }

    private StockReservedEvent stockFailed(String orderId, String sku, int qty, String outcome) {
        return new StockReservedEvent(UUID.randomUUID().toString(), orderId, sku, qty,
                outcome, -1, "cause-" + orderId, Instant.now());
    }

    private PaymentProcessedEvent payment(String orderId, String status, String reason, BigDecimal amount) {
        return new PaymentProcessedEvent(UUID.randomUUID().toString(), orderId, "Ada", amount,
                status, reason, "cause-" + orderId, Instant.now());
    }

    @Test
    @DisplayName("HAPPY: reserved + approved → order confirmed & SHIPPED and an OrderShipped is emitted")
    void happyPathShipsTheOrder() {
        seed("ORD-A", "KEYBOARD-001", 2);

        producer().send(INVENTORY_EVENTS, "ORD-A", reserved("ORD-A", "KEYBOARD-001", 2, 40));
        producer().send(PAYMENT_EVENTS, "ORD-A", payment("ORD-A", "APPROVED", "APPROVED", new BigDecimal("200.00")));

        // (1) the order the saga OWNS advances to SHIPPED (PLACED → CONFIRMED → SHIPPED).
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(orders.findById("ORD-A").orElseThrow().getStatus()).isEqualTo(OrderStatus.SHIPPED));

        // (2) the saga announces it with an OrderShipped carrying the charged amount.
        OrderShippedEvent shipped = awaitShipped("ORD-A");
        assertThat(shipped.status()).isEqualTo("SHIPPED");
        assertThat(shipped.item()).isEqualTo("KEYBOARD-001");
        assertThat(shipped.quantity()).isEqualTo(2);
        assertThat(shipped.amount()).isEqualByComparingTo(new BigDecimal("200.00"));
    }

    @Test
    @DisplayName("COMPENSATE: payment DECLINED → order CANCELLED and an OrderCancelled(PAYMENT_DECLINED) is emitted")
    void declineCompensatesTheOrder() {
        seed("ORD-D", "MONITOR-4K", 20);

        // Only the payment result arrives, and it is a decline — the saga must NOT wait for the stock leg.
        producer().send(PAYMENT_EVENTS, "ORD-D",
                payment("ORD-D", "DECLINED", "AMOUNT_OVER_LIMIT", new BigDecimal("2000.00")));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(orders.findById("ORD-D").orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED));

        OrderCancelledEvent cancelled = awaitCancelled("ORD-D");
        assertThat(cancelled.reason()).isEqualTo("PAYMENT_DECLINED");
        assertThat(cancelled.sku()).isEqualTo("MONITOR-4K");
        assertThat(cancelled.quantity()).isEqualTo(20);
    }

    @Test
    @DisplayName("COMPENSATE: stock could not be reserved → order CANCELLED and OrderCancelled(STOCK_UNAVAILABLE)")
    void stockFailureCompensatesTheOrder() {
        seed("ORD-S", "STAND-001", 1);

        producer().send(INVENTORY_EVENTS, "ORD-S", stockFailed("ORD-S", "STAND-001", 1, "INSUFFICIENT_STOCK"));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(orders.findById("ORD-S").orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED));

        OrderCancelledEvent cancelled = awaitCancelled("ORD-S");
        assertThat(cancelled.reason()).isEqualTo("STOCK_UNAVAILABLE");
    }

    @Test
    @DisplayName("IDEMPOTENT: a redelivered result never ships or cancels twice — exactly one terminal event")
    void redeliveryIsIdempotent() {
        seed("ORD-I", "HUB-001", 1);

        StockReservedEvent res = reserved("ORD-I", "HUB-001", 1, 14);
        PaymentProcessedEvent pay = payment("ORD-I", "APPROVED", "APPROVED", new BigDecimal("100.00"));
        KafkaTemplate<String, Object> p = producer();
        // AT-LEAST-ONCE: both legs delivered twice.
        p.send(INVENTORY_EVENTS, "ORD-I", res);
        p.send(PAYMENT_EVENTS, "ORD-I", pay);
        p.send(INVENTORY_EVENTS, "ORD-I", res);
        p.send(PAYMENT_EVENTS, "ORD-I", pay);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(orders.findById("ORD-I").orElseThrow().getStatus()).isEqualTo(OrderStatus.SHIPPED));

        // Exactly one OrderShipped, and NO OrderCancelled, despite the doubled deliveries of both legs.
        assertThat(drainShipped("ORD-I", Duration.ofSeconds(4))).hasSize(1);
        assertThat(drainCancelled("ORD-I", Duration.ofSeconds(3))).isEmpty();
    }
}
