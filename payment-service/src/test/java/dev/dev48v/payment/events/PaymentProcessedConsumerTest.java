package dev.dev48v.payment.events;

import dev.dev48v.payment.config.KafkaConsumerConfig;
import dev.dev48v.payment.config.KafkaProducerConfig;
import dev.dev48v.payment.payment.PaymentLedger;
import dev.dev48v.payment.payment.PaymentService;
import dev.dev48v.payment.payment.PaymentStatus;
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
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Day 27 — proves the whole event-driven service end to end WITHOUT a real, external broker: @EmbeddedKafka
// stands up a throwaway in-JVM Kafka broker for this test class, so the build needs no Docker and no running
// Kafka. We build a SLICE of the app — the REAL consumer + producer beans (KafkaConsumerConfig +
// KafkaProducerConfig), the listener, the decision service, the ledger and the result publisher — then
// PUBLISH an OrderPlaced (as JSON, exactly like order-service's producer) and assert TWO things: payment-service
// (1) RECORDS a decided Payment on its ledger, and (2) EMITS a matching PaymentProcessed onto payment-events.
// Both the APPROVE path and the DECLINE path are proven, plus idempotency (a redelivered order is not charged
// or emitted twice).
//
// Why a sliced context (explicit classes) rather than the whole app: it keeps the test about the event flow —
// no Eureka, no config server — while still exercising the genuine consume-decide-emit path. @EnableKafka lives
// on KafkaConsumerConfig so the @KafkaListener is detected even in this sliced (no auto-config) context. The
// @Value broker addresses resolve to the embedded broker via the property below, so the same code that runs in
// production runs here.
@SpringBootTest(
        classes = PaymentProcessedConsumerTest.TestApp.class,
        properties = {
                "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
                "payment.events.enabled=true",
                "payment.events.order-placed-topic=order-placed",
                "payment.events.payment-events-topic=payment-events",
                "payment.events.consumer-group-id=payment-consumer-test",
                "payment.events.unit-price=100.00",
                "payment.events.decline-threshold=1000.00",
                "payment.events.decline-customer=DECLINE"
        })
@EmbeddedKafka(partitions = 1, topics = {"order-placed", "payment-events"})
@DisplayName("Day 27 · payment-service consumes OrderPlaced, records a Payment, and emits PaymentProcessed")
class PaymentProcessedConsumerTest {

    private static final String ORDER_PLACED = "order-placed";
    private static final String PAYMENT_EVENTS = "payment-events";

    // The real consumer + producer beans + a real PaymentService + ledger + publisher. A plain (lite) config
    // passed as the sole classes = ... so Spring Boot keeps the context SLICED and does not fall back to the
    // full @SpringBootApplication.
    @Import({KafkaConsumerConfig.class, KafkaProducerConfig.class, OrderPlacedListener.class,
            PaymentService.class, PaymentLedger.class, PaymentResultPublisher.class})
    static class TestApp {
    }

    @Autowired
    private PaymentLedger ledger;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    // A JSON producer that mirrors order-service's real producer (JSON value, NO type headers), so the bytes on
    // the topic are exactly what payment-service decodes in production.
    private KafkaTemplate<String, OrderPlacedEvent> orderProducer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);   // language-neutral payload, like the real producer
        ProducerFactory<String, OrderPlacedEvent> pf = new DefaultKafkaProducerFactory<>(props);
        return new KafkaTemplate<>(pf);
    }

    // A consumer on payment-events that decodes the emitted PaymentProcessed results the same way a real
    // downstream service would (JSON, no type headers, trusted packages). A UNIQUE group id + earliest means it
    // reads every result on the topic from the beginning, so tests can filter by order id without cross-talk.
    private Consumer<String, PaymentProcessedEvent> paymentEventsConsumer() {
        Map<String, Object> props = KafkaTestUtils.consumerProps(
                "payment-events-test-" + UUID.randomUUID(), "true", embeddedKafka);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        JsonDeserializer<PaymentProcessedEvent> vd = new JsonDeserializer<>(PaymentProcessedEvent.class);
        vd.setUseTypeHeaders(false);
        vd.addTrustedPackages("dev.dev48v.*");
        Consumer<String, PaymentProcessedEvent> consumer =
                new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), vd).createConsumer();
        // Subscribe + wait for partition assignment; with a fresh group + earliest it reads from the start.
        embeddedKafka.consumeFromAnEmbeddedTopic(consumer, PAYMENT_EVENTS);
        return consumer;
    }

    private OrderPlacedEvent event(String eventId, String orderId, String customer, String item, int quantity) {
        return new OrderPlacedEvent(eventId, orderId, customer, item, quantity,
                "PLACED", Instant.now(), Instant.now());
    }

    // Poll payment-events until a result for `orderId` appears (or fail after the timeout). Robust to the
    // asynchronous consume-decide-emit hop: it keeps polling, accumulating offset, until it sees our order.
    private PaymentProcessedEvent awaitResultFor(String orderId) {
        Consumer<String, PaymentProcessedEvent> consumer = paymentEventsConsumer();
        AtomicReference<PaymentProcessedEvent> found = new AtomicReference<>();
        try {
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                ConsumerRecords<String, PaymentProcessedEvent> recs = consumer.poll(Duration.ofMillis(300));
                recs.forEach(r -> {
                    if (orderId.equals(r.value().orderId())) {
                        found.set(r.value());
                    }
                });
                assertThat(found.get()).isNotNull();
            });
        } finally {
            consumer.close();
        }
        return found.get();
    }

    // Drain every payment-events result over a fixed window (used to COUNT emissions for the idempotency proof).
    private List<PaymentProcessedEvent> drainResults(Duration window) {
        Consumer<String, PaymentProcessedEvent> consumer = paymentEventsConsumer();
        List<PaymentProcessedEvent> out = new ArrayList<>();
        try {
            long end = System.currentTimeMillis() + window.toMillis();
            while (System.currentTimeMillis() < end) {
                ConsumerRecords<String, PaymentProcessedEvent> recs = consumer.poll(Duration.ofMillis(300));
                recs.forEach(r -> out.add(r.value()));
            }
        } finally {
            consumer.close();
        }
        return out;
    }

    @Test
    @DisplayName("an in-limit order is APPROVED — Payment recorded and PaymentProcessed(APPROVED) emitted")
    void approvePathRecordsAndEmits() {
        String causeId = UUID.randomUUID().toString();
        orderProducer().send(ORDER_PLACED, "ORD-A", event(causeId, "ORD-A", "Ada", "KEYBOARD-001", 2));

        // (1) the Payment is recorded on the ledger — decided APPROVED, amount = 2 × 100.00 = 200.00.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(ledger.forOrder("ORD-A")).isPresent());
        var payment = ledger.forOrder("ORD-A").orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(payment.getAmount()).isEqualByComparingTo(new BigDecimal("200.00"));

        // (2) a matching PaymentProcessed is emitted onto payment-events, tracing back to the OrderPlaced.
        PaymentProcessedEvent result = awaitResultFor("ORD-A");
        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.reason()).isEqualTo("APPROVED");
        assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(result.causedByEventId()).isEqualTo(causeId);
    }

    @Test
    @DisplayName("an over-limit order is DECLINED — Payment recorded and PaymentProcessed(DECLINED) emitted")
    void declinePathRecordsAndEmits() {
        // 20 × 100.00 = 2000.00, over the 1000.00 threshold → declined as over-limit.
        orderProducer().send(ORDER_PLACED, "ORD-D",
                event(UUID.randomUUID().toString(), "ORD-D", "Grace", "MONITOR-4K", 20));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(ledger.forOrder("ORD-D")).isPresent());
        assertThat(ledger.forOrder("ORD-D").orElseThrow().getStatus()).isEqualTo(PaymentStatus.DECLINED);

        PaymentProcessedEvent result = awaitResultFor("ORD-D");
        assertThat(result.status()).isEqualTo("DECLINED");
        assertThat(result.reason()).isEqualTo("AMOUNT_OVER_LIMIT");
        assertThat(result.isApproved()).isFalse();
    }

    @Test
    @DisplayName("a 'test card' customer is DECLINED regardless of amount")
    void testCardCustomerIsDeclined() {
        // Small in-limit amount (1 × 100.00), but the configured decline-customer forces a decline.
        orderProducer().send(ORDER_PLACED, "ORD-T",
                event(UUID.randomUUID().toString(), "ORD-T", "DECLINE", "HUB-001", 1));

        PaymentProcessedEvent result = awaitResultFor("ORD-T");
        assertThat(result.status()).isEqualTo("DECLINED");
        assertThat(result.reason()).isEqualTo("TEST_CARD_DECLINED");
    }

    @Test
    @DisplayName("a duplicate OrderPlaced (same order id) is NOT charged twice — idempotent, one result emitted")
    void duplicateOrderIsNotChargedTwice() {
        OrderPlacedEvent dup = event(UUID.randomUUID().toString(), "ORD-M", "Linus", "MOUSE-001", 3);
        KafkaTemplate<String, OrderPlacedEvent> producer = orderProducer();
        producer.send(ORDER_PLACED, "ORD-M", dup);
        producer.send(ORDER_PLACED, "ORD-M", dup);   // AT-LEAST-ONCE redelivery of the SAME event
        // A sentinel on a different order. With a single partition records are consumed in send order, so once
        // the sentinel is handled we KNOW both duplicate deliveries were already processed.
        producer.send(ORDER_PLACED, "ORD-SENTINEL",
                event(UUID.randomUUID().toString(), "ORD-SENTINEL", "Ada", "HUB-001", 1));

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(ledger.forOrder("ORD-SENTINEL")).isPresent());

        // Charged exactly once despite two deliveries.
        assertThat(ledger.forOrder("ORD-M")).isPresent();

        // And exactly ONE PaymentProcessed was emitted for ORD-M (a redelivery emits nothing).
        long emittedForOrderM = drainResults(Duration.ofSeconds(4)).stream()
                .filter(e -> "ORD-M".equals(e.orderId()))
                .count();
        assertThat(emittedForOrderM).isEqualTo(1L);
    }
}
