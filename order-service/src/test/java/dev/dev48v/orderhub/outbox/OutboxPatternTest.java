package dev.dev48v.orderhub.outbox;

import dev.dev48v.orderhub.domain.Order;
import dev.dev48v.orderhub.config.OrderProperties;
import dev.dev48v.orderhub.events.OrderEventPublisher;
import dev.dev48v.orderhub.inventory.InventoryServiceClient;
import dev.dev48v.orderhub.persistence.JpaOrderRepository;
import dev.dev48v.orderhub.persistence.OrderEntity;
import dev.dev48v.orderhub.persistence.SpringDataOrderRepository;
import dev.dev48v.orderhub.repository.OrderRepository;
import dev.dev48v.orderhub.service.OrderService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

// Day 30 — proves the TRANSACTIONAL OUTBOX end to end WITHOUT Docker or an external broker, mirroring how the
// Day-25/28/29 tests stay hermetic. @EmbeddedKafka stands up an in-JVM broker, and a SLICED context wires a
// REAL JPA stack over H2 (the same Flyway migrations — V1/V2/V3 — that run in production) so the outbox_event
// table genuinely exists and the same-tx write can be observed. The slice pulls in ONLY what the outbox needs
// — DataSource + JPA + Flyway + Jackson + transaction management — plus the real outbox beans, the real
// JpaOrderRepository, and a real OrderService; the inventory client and the Day-25 publisher are mocked (with
// the outbox ON, placeOrder never calls the publisher). No Redis/cache (CacheConfig isn't imported, so
// @CacheEvict is inert), no Eureka, no web — the outbox is the only thing under test.
//
// The outbox is switched ON but the relay's AUTO-poll is OFF, so a test can observe a written-but-unpublished
// row and then drive the relay by hand — the two halves of the pattern. Three things are proven:
//   • SAME-TX WRITE — placing an order records an OrderPlaced row in the outbox in the SAME transaction as the
//                     order; with the relay off, that row is RETAINED, unpublished (never lost).
//   • RELAY         — the relay publishes each unsent row (keyed by the order id) then marks it sent; a re-run
//                     publishes nothing new (idempotent, exactly-once effect).
//   • ATOMICITY     — the order INSERT and the outbox INSERT commit or ROLL BACK together.
@SpringBootTest(
        classes = OutboxPatternTest.TestApp.class,
        properties = {
                "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
                "orderhub.outbox.enabled=true",
                "orderhub.outbox.relay-enabled=false",     // relay by hand so retention is observable
                "orderhub.outbox.topic=order-placed",
                // A dedicated H2 schema for this test; Flyway (V1/V2/V3) creates the tables, Hibernate validates.
                "spring.datasource.url=jdbc:h2:mem:outboxtest;DB_CLOSE_DELAY=-1",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.flyway.enabled=true"
        })
@EmbeddedKafka(partitions = 1, topics = "order-placed")
@DisplayName("Day 30 · transactional outbox: same-tx write, relay publishes & marks sent, order+outbox are atomic")
class OutboxPatternTest {

    private static final String TOPIC = "order-placed";

    // A SLICED application: real JPA over H2 (+ Flyway + transactions) + the real outbox beans + OrderService.
    // Deliberately NOT a @SpringBootApplication, so Spring Boot keeps the context to exactly these pieces and
    // never drags in the full app (Redis, Eureka, web, the saga/orchestration listeners).
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class,
            JacksonAutoConfiguration.class,
            TransactionAutoConfiguration.class      // enables @Transactional processing on placeOrder + the relay
    })
    @EntityScan(basePackageClasses = {OrderEntity.class, OutboxEvent.class})
    @EnableJpaRepositories(basePackageClasses = {SpringDataOrderRepository.class, OutboxEventRepository.class})
    @Import({OutboxKafkaConfig.class, OutboxWriter.class, OutboxRelay.class, JpaOrderRepository.class})
    static class TestApp {
        @Bean
        OrderProperties orderProperties() {
            return new OrderProperties(1000, 20, 100);
        }

        @Bean
        OrderService orderService(OrderRepository repository,
                                  OrderProperties properties,
                                  InventoryServiceClient inventory,
                                  OrderEventPublisher events,
                                  OutboxWriter outbox) {
            return new OrderService(repository, properties, inventory, events, outbox);
        }
    }

    // Mocked so no inventory-service and no broker-typed producer are needed. With the outbox ON, placeOrder
    // reserves stock (mock returns null — tolerated) and then writes the outbox row instead of publishing, so
    // the publisher is never invoked.
    @MockBean
    private InventoryServiceClient inventory;

    @MockBean
    private OrderEventPublisher events;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OutboxEventRepository outboxRepository;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @Autowired
    private PlatformTransactionManager txManager;

    // H2 is a singleton across the class, so start each test from a clean outbox — assertions on the row set
    // then reflect only the order this test placed.
    @BeforeEach
    void cleanOutbox() {
        outboxRepository.deleteAll();
    }

    @Test
    @DisplayName("placing an order writes an OrderPlaced outbox row IN THE SAME TX, retained unpublished while the relay is off")
    void placingAnOrderWritesOutboxRowInSameTransaction() {
        Order placed = orderService.placeOrder("Ada", "KEYBOARD-001", 2);

        // The order itself is durably persisted.
        assertThat(orderRepository.findById(placed.getId())).isPresent();

        // And EXACTLY one outbox row was written alongside it — still unsent.
        List<OutboxEvent> rows = outboxRepository.findAll();
        assertThat(rows).hasSize(1);
        OutboxEvent row = rows.get(0);
        assertThat(row.getEventType()).isEqualTo("OrderPlaced");
        assertThat(row.getAggregateType()).isEqualTo("Order");
        assertThat(row.getAggregateId()).isEqualTo(placed.getId());   // = the Kafka message key
        assertThat(row.getTopic()).isEqualTo(TOPIC);
        assertThat(row.getEventId()).isNotBlank();
        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(row.isProcessed()).isFalse();
        assertThat(row.getSentAt()).isNull();

        // The payload is the OrderPlaced event serialized to JSON, stored verbatim.
        assertThat(row.getPayload())
                .contains("\"orderId\":\"" + placed.getId() + "\"")
                .contains("\"customer\":\"Ada\"")
                .contains("\"item\":\"KEYBOARD-001\"")
                .contains("\"quantity\":2")
                .contains("\"status\":\"PLACED\"");

        // RETENTION: with the relay disabled, the event has NOT reached Kafka — it sits safely in the outbox,
        // not lost. (Scoped to this order's key, so it holds regardless of what other methods put on the topic.)
        assertThat(recordsFor(placed.getId(), Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    @DisplayName("the relay publishes each unsent row (keyed by order id) then marks it sent — a re-run publishes nothing new")
    void relayPublishesUnsentRowsThenMarksThemSentIdempotently() {
        Order placed = orderService.placeOrder("Grace", "MOUSE-001", 3);
        String orderId = placed.getId();

        // Precondition: the row exists and is unsent.
        assertThat(outboxRepository.findAll()).singleElement()
                .satisfies(r -> assertThat(r.isProcessed()).isFalse());

        // Drive the relay on demand (this bypasses the disabled auto-poll and publishes one batch).
        int published = relay.relayOnce();
        assertThat(published).isEqualTo(1);

        // The event is now on the topic — keyed by the order id, with the recorded JSON intact.
        ConsumerRecord<String, String> record = awaitRecordFor(orderId);
        assertThat(record.key()).isEqualTo(orderId);
        assertThat(record.value())
                .contains("\"orderId\":\"" + orderId + "\"")
                .contains("\"item\":\"MOUSE-001\"")
                .contains("\"quantity\":3");

        // The row is now marked sent (processed + a sent-at stamp), so it won't be scanned again.
        OutboxEvent row = outboxRepository.findAll().get(0);
        assertThat(row.isProcessed()).isTrue();
        assertThat(row.getSentAt()).isNotNull();

        // IDEMPOTENT: no rows remain unsent, so a second relay publishes ZERO...
        assertThat(relay.relayOnce()).isZero();
        // ...and there is exactly ONE record on the topic for this order — never a duplicate from the re-run.
        assertThat(recordsFor(orderId, Duration.ofSeconds(4))).hasSize(1);
    }

    @Test
    @DisplayName("the order INSERT and the outbox INSERT commit or ROLL BACK together (one shared transaction)")
    void orderAndOutboxCommitOrRollBackTogether() {
        String orderId = "ROLLBACK-" + UUID.randomUUID();

        // Run the SAME two writes placeOrder does — save the order, then append its outbox row — inside one
        // transaction we deliberately fail AFTER both. Because they share the transaction, the rollback must
        // undo BOTH; if they were independent writes, the order (or the row) would leak.
        TransactionTemplate tx = new TransactionTemplate(txManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            Order order = orderRepository.save(new Order(orderId, "Rita", "STAND-001", 1));
            outboxWriter.append(order);
            throw new IllegalStateException("boom AFTER both writes");
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("boom");

        // Neither the order nor an outbox row survived — proof they were one atomic unit.
        assertThat(orderRepository.findById(orderId)).isEmpty();
        assertThat(outboxRepository.count()).isZero();
    }

    // ---- helpers: read the order-placed topic off the embedded broker ------------------------------------

    private Consumer<String, String> newConsumer() {
        Map<String, Object> props =
                KafkaTestUtils.consumerProps("day30-" + UUID.randomUUID(), "true", embeddedKafka);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                props, new StringDeserializer(), new StringDeserializer()).createConsumer();
        consumer.subscribe(List.of(TOPIC));
        return consumer;
    }

    // Wait (up to 20s) for the FIRST record on the topic whose key is the given order id.
    private ConsumerRecord<String, String> awaitRecordFor(String orderId) {
        Consumer<String, String> consumer = newConsumer();
        try {
            List<ConsumerRecord<String, String>> found = new ArrayList<>();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                ConsumerRecords<String, String> recs = consumer.poll(Duration.ofMillis(300));
                recs.forEach(r -> { if (orderId.equals(r.key())) found.add(r); });
                assertThat(found).isNotEmpty();
            });
            return found.get(0);
        } finally {
            consumer.close();
        }
    }

    // Drain the topic for a fixed window and return every record whose key is the given order id.
    private List<ConsumerRecord<String, String>> recordsFor(String orderId, Duration window) {
        Consumer<String, String> consumer = newConsumer();
        List<ConsumerRecord<String, String>> out = new ArrayList<>();
        try {
            long end = System.currentTimeMillis() + window.toMillis();
            while (System.currentTimeMillis() < end) {
                ConsumerRecords<String, String> recs = consumer.poll(Duration.ofMillis(300));
                recs.forEach(r -> { if (orderId.equals(r.key())) out.add(r); });
            }
        } finally {
            consumer.close();
        }
        return out;
    }
}
