package dev.dev48v.orderhub.events;

import dev.dev48v.orderhub.config.KafkaProducerConfig;
import dev.dev48v.orderhub.config.OrderProperties;
import dev.dev48v.orderhub.domain.Order;
import dev.dev48v.orderhub.inventory.InventoryServiceClient;
import dev.dev48v.orderhub.inventory.ReserveRequest;
import dev.dev48v.orderhub.inventory.StockView;
import dev.dev48v.orderhub.outbox.OutboxProperties;
import dev.dev48v.orderhub.outbox.OutboxWriter;
import dev.dev48v.orderhub.repository.OrderRepository;
import dev.dev48v.orderhub.service.OrderService;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

// Day 25 — proves the producer end to end WITHOUT a real, external broker: @EmbeddedKafka stands up a
// throwaway in-JVM Kafka broker for this test class, so the build needs no Docker and no running Kafka.
// We build a tiny slice of the app — the real OrderService wired to the REAL Kafka producer beans
// (KafkaProducerConfig + OrderEventPublisher), with the repository and the inventory client mocked — then
// place an order and assert an OrderPlaced record actually lands on the `order-placed` topic.
//
// Why a sliced context (explicit classes) rather than the whole app: it keeps the test about Kafka only —
// no web server, no Postgres/Redis, no discovery — while still exercising the genuine create path
// (validate -> reserve -> save -> PUBLISH). The @Value broker address in KafkaProducerConfig resolves to
// the embedded broker via the property below, so the same code that runs in production runs here.
@SpringBootTest(
        classes = OrderPlacedEventTest.TestApp.class,
        properties = {
                "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
                "orderhub.events.enabled=true",
                "orderhub.events.order-placed-topic=order-placed"
        })
@EmbeddedKafka(partitions = 1, topics = "order-placed")
@DisplayName("Day 25 · placing an order produces an OrderPlaced event to Kafka")
class OrderPlacedEventTest {

    private static final String TOPIC = "order-placed";

    // The real producer beans + publisher + a real OrderService; the storage/network dependencies are mocked
    // so the test is hermetic. @Import pulls in KafkaProducerConfig (ProducerFactory + KafkaTemplate, and it
    // @EnableConfigurationProperties(OrderEventProperties.class)) and the OrderEventPublisher component.
    //
    // NOTE: this is a plain (lite) config, deliberately NOT a @TestConfiguration — the same shape as
    // InventoryServiceClientTest.FeignTestConfig. Two reasons: (1) because it is not a @TestComponent, passing
    // it as the sole @SpringBootTest(classes = ...) keeps the context SLICED — Spring Boot does not fall back
    // to searching for and adding the primary @SpringBootApplication (OrderHubApplication), which would drag in
    // the whole app and a second OrderProperties bean; (2) with no @Configuration/@Component it is invisible to
    // the app's component scan, so its @Bean methods never leak into the full-stack integration tests.
    @Import({KafkaProducerConfig.class, OrderEventPublisher.class})
    static class TestApp {
        @Bean
        OrderProperties orderProperties() {
            return new OrderProperties(1000, 20, 100);
        }

        // Day 30 — the outbox is DISABLED in this Day-25 producer slice, so placeOrder keeps its direct-publish
        // path (the behaviour this test asserts). A disabled writer short-circuits on !enabled before touching
        // its repository or mapper, so the nulls here are inert — it never uses them.
        @Bean
        OutboxWriter outboxWriter() {
            return new OutboxWriter(null, null,
                    new OutboxProperties(false, "order-placed", "Order", true, 200));
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

    // Mocked so no database and no inventory-service are needed: save echoes back the order, and the
    // reservation succeeds so placeOrder reaches the publish step.
    @MockBean
    private OrderRepository repository;

    @MockBean
    private InventoryServiceClient inventory;

    @Autowired
    private OrderService orderService;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    @Test
    @DisplayName("placeOrder emits an OrderPlaced (keyed by order id, JSON payload) to the order-placed topic")
    void placingAnOrderProducesOrderPlacedEvent() {
        when(repository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        when(inventory.reserve(eq("KEYBOARD-001"), any(ReserveRequest.class)))
                .thenReturn(new StockView("KEYBOARD-001", "Mechanical keyboard", 40, true));

        // A consumer on the embedded broker, reading the topic from the beginning.
        Map<String, Object> consumerProps =
                KafkaTestUtils.consumerProps("day25-test-group", "true", embeddedKafka);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(
                consumerProps, new StringDeserializer(), new StringDeserializer()).createConsumer();
        consumer.subscribe(Collections.singletonList(TOPIC));

        try {
            // Run the REAL create flow — this is what "an order is created" means end to end.
            Order placed = orderService.placeOrder("Ada", "KEYBOARD-001", 2);

            // The event must have been produced to the topic.
            ConsumerRecord<String, String> record =
                    KafkaTestUtils.getSingleRecord(consumer, TOPIC, Duration.ofSeconds(15));

            assertThat(record).isNotNull();
            // Keyed by the order id, so all events for one order stay ordered on one partition.
            assertThat(record.key()).isEqualTo(placed.getId());

            // The JSON payload carries the key order fields + timestamps.
            String json = record.value();
            assertThat(json)
                    .contains("\"orderId\":\"" + placed.getId() + "\"")
                    .contains("\"customer\":\"Ada\"")
                    .contains("\"item\":\"KEYBOARD-001\"")
                    .contains("\"quantity\":2")
                    .contains("\"status\":\"PLACED\"")
                    .contains("\"eventId\":")
                    .contains("\"placedAt\":")
                    .contains("\"occurredAt\":");
        } finally {
            consumer.close();
        }
    }
}
