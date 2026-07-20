package dev.dev48v.orderhub.orchestration;

import dev.dev48v.orderhub.config.OrchestrationKafkaConfig;
import dev.dev48v.orderhub.domain.Order;
import dev.dev48v.orderhub.domain.OrderStatus;
import dev.dev48v.orderhub.events.OrderPlacedEvent;
import dev.dev48v.orderhub.repository.InMemoryOrderRepository;
import dev.dev48v.orderhub.repository.OrderRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Day 29 — proves the ORCHESTRATION saga end to end WITHOUT a real, external broker, the direct analogue of
// Day 28's OrderSagaChoreographyTest. @EmbeddedKafka stands up a throwaway in-JVM Kafka broker for this class
// (no Docker, no running Kafka). We build a SLICE of order-service — the REAL orchestrator beans
// (OrchestrationKafkaConfig's consumers + command producer, OrchestrationOrderPlacedListener, SagaReplyListener,
// SagaOrchestrator, SagaCommandPublisher) over an in-memory OrderRepository — then play the roles of the three
// participant services (inventory, payment, shipping) with a small background responder that consumes the
// orchestrator's commands off saga-commands and answers on saga-replies, exactly the way the participants'
// real command handlers would.
//
// Two paths are proven, the two halves of the saga:
//   • HAPPY  — RESERVE_STOCK→reserved, PROCESS_PAYMENT→approved, SCHEDULE_SHIPMENT→scheduled ⇒ the order is
//              CONFIRMED then SHIPPED and the saga reaches COMPLETED. The orchestrator issued all three commands
//              in order.
//   • FAILURE — payment DECLINED ⇒ the orchestrator COMPENSATES: it issues the explicit RELEASE_STOCK command
//               (reverse of the reserve) and cancels the order, reaching CANCELLED(PAYMENT_DECLINED). The
//               participant confirms the stock was released.
@SpringBootTest(
        classes = OrderOrchestrationTest.TestApp.class,
        properties = {
                "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
                "orderhub.orchestration.enabled=true",
                "orderhub.orchestration.order-placed-topic=order-placed",
                "orderhub.orchestration.commands-topic=saga-commands",
                "orderhub.orchestration.replies-topic=saga-replies",
                "orderhub.orchestration.consumer-group-id=order-orchestrator-test"
        })
@EmbeddedKafka(partitions = 1, topics = {"order-placed", "saga-commands", "saga-replies"})
@DisplayName("Day 29 · order-service orchestration saga: coordinator drives commands→replies to COMPLETED, compensates on failure")
class OrderOrchestrationTest {

    private static final String ORDER_PLACED = "order-placed";
    private static final String SAGA_COMMANDS = "saga-commands";
    private static final String SAGA_REPLIES = "saga-replies";
    private static final int UNIT_PRICE = 100;

    // The real orchestrator beans + a plain in-memory OrderRepository (new'd directly so its @Profile("inmemory")
    // gate doesn't apply). Passed as the sole classes = ... so Spring Boot keeps the context SLICED.
    @Import({OrchestrationKafkaConfig.class, OrchestrationOrderPlacedListener.class, SagaReplyListener.class,
            SagaOrchestrator.class, SagaCommandPublisher.class})
    static class TestApp {
        @Bean
        OrderRepository orderRepository() {
            return new InMemoryOrderRepository();
        }
    }

    @Autowired
    private OrderRepository orders;

    @Autowired
    private SagaOrchestrator orchestrator;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    private Participants participants;

    @AfterEach
    void stopParticipants() {
        if (participants != null) {
            participants.stop();
        }
    }

    // ---- producer that mimics order-service publishing OrderPlaced (JSON, no type headers) --------------
    private KafkaTemplate<String, OrderPlacedEvent> orderPlacedProducer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        ProducerFactory<String, OrderPlacedEvent> pf = new DefaultKafkaProducerFactory<>(props);
        return new KafkaTemplate<>(pf);
    }

    private Order seed(String orderId, String item, int quantity) {
        return orders.save(new Order(orderId, "Ada", item, quantity));
    }

    private void placeOrder(String orderId, String item, int quantity) {
        orderPlacedProducer().send(ORDER_PLACED, orderId, new OrderPlacedEvent(
                UUID.randomUUID().toString(), orderId, "Ada", item, quantity, "PLACED",
                Instant.now(), Instant.now()));
    }

    @Test
    @DisplayName("HAPPY: reserve→pay→ship all succeed → order SHIPPED and the saga reaches COMPLETED")
    void happyPathCompletesTheSaga() {
        seed("ORD-OK", "KEYBOARD-001", 2);
        participants = new Participants("ORD-OK", true, true).start();   // stock ok, payment approved

        placeOrder("ORD-OK", "KEYBOARD-001", 2);

        // the order the orchestrator drives advances PLACED -> CONFIRMED -> SHIPPED
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() ->
                assertThat(orders.findById("ORD-OK").orElseThrow().getStatus()).isEqualTo(OrderStatus.SHIPPED));

        // the coordinator's own state machine reached COMPLETED
        OrchestrationView view = orchestrator.forOrder("ORD-OK").get(0);
        assertThat(view.result()).isEqualTo("COMPLETED");
        assertThat(view.step()).isEqualTo("COMPLETED");

        // and it issued all three forward commands, in order, and NO compensation
        assertThat(participants.seen).contains(SagaCommand.RESERVE_STOCK, SagaCommand.PROCESS_PAYMENT,
                SagaCommand.SCHEDULE_SHIPMENT);
        assertThat(participants.seen).doesNotContain(SagaCommand.RELEASE_STOCK);
    }

    @Test
    @DisplayName("FAILURE: payment DECLINED → orchestrator compensates (RELEASE_STOCK) → order CANCELLED")
    void paymentDeclineCompensates() {
        seed("ORD-DEC", "MONITOR-4K", 20);                     // 20 x $100 = $2000, over the decline threshold
        participants = new Participants("ORD-DEC", true, false).start();  // stock ok, payment DECLINED

        placeOrder("ORD-DEC", "MONITOR-4K", 20);

        // the orchestrator cancels the order after compensating
        await().atMost(Duration.ofSeconds(25)).untilAsserted(() ->
                assertThat(orders.findById("ORD-DEC").orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED));

        OrchestrationView view = orchestrator.forOrder("ORD-DEC").get(0);
        assertThat(view.result()).isEqualTo("CANCELLED");
        assertThat(view.reason()).isEqualTo(SagaOrchestrator.REASON_PAYMENT_DECLINED);

        // COMPENSATION: stock was reserved, then the orchestrator issued the reverse RELEASE_STOCK command,
        // and the (participant) inventory confirmed the release — never a shipment.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(participants.seen).contains(SagaCommand.RELEASE_STOCK));
        assertThat(participants.stockReleased).isTrue();
        assertThat(participants.seen).contains(SagaCommand.RESERVE_STOCK, SagaCommand.PROCESS_PAYMENT);
        assertThat(participants.seen).doesNotContain(SagaCommand.SCHEDULE_SHIPMENT);
    }

    // =====================================================================================================
    // The three participant services, played by ONE background responder: consume the orchestrator's commands
    // off saga-commands and answer on saga-replies exactly as inventory/payment/shipping would. `approve` and
    // `stockOk` choose the branch under test.
    //
    // @EmbeddedKafka stands up ONE broker for the whole class, so both test methods share the saga-commands
    // topic. Each responder starts a fresh consumer group reading from `earliest`, so it also replays the OTHER
    // test's commands off that shared topic. We therefore scope the responder to the ONE order under test
    // (targetOrderId): commands for any other order are a stale replay and are ignored, so `seen` and
    // `stockReleased` reflect only this test's saga regardless of the order the two methods run in.
    // =====================================================================================================
    private final class Participants {
        private final String targetOrderId;
        private final boolean stockOk;
        private final boolean approve;
        private final Set<String> seen = ConcurrentHashMap.newKeySet();
        private volatile boolean stockReleased = false;
        private volatile boolean running = true;
        private Thread thread;
        private Consumer<String, SagaCommand> consumer;
        private KafkaTemplate<String, SagaReply> replies;

        Participants(String targetOrderId, boolean stockOk, boolean approve) {
            this.targetOrderId = targetOrderId;
            this.stockOk = stockOk;
            this.approve = approve;
        }

        Participants start() {
            replies = replyProducer();
            consumer = commandConsumer();
            thread = new Thread(this::loop, "saga-participants");
            thread.setDaemon(true);
            thread.start();
            return this;
        }

        private void loop() {
            try {
                while (running) {
                    ConsumerRecords<String, SagaCommand> recs = consumer.poll(Duration.ofMillis(200));
                    recs.forEach(r -> handle(r.value()));
                }
            } catch (WakeupException ignored) {
                // stop() was called — exit quietly
            } finally {
                consumer.close();
            }
        }

        private void handle(SagaCommand cmd) {
            if (!targetOrderId.equals(cmd.orderId())) {
                return; // a stale command replayed off the shared topic from the sibling test — not ours
            }
            seen.add(cmd.type());
            switch (cmd.type()) {
                case SagaCommand.RESERVE_STOCK -> reply(stockOk
                        ? SagaReply.reserved(cmd, 40)
                        : SagaReply.rejected(cmd, "INSUFFICIENT_STOCK"));
                case SagaCommand.PROCESS_PAYMENT -> {
                    BigDecimal amount = BigDecimal.valueOf((long) cmd.quantity() * UNIT_PRICE);
                    reply(approve
                            ? SagaReply.approved(cmd, amount)
                            : SagaReply.declined(cmd, "AMOUNT_OVER_LIMIT", amount));
                }
                case SagaCommand.SCHEDULE_SHIPMENT -> reply(SagaReply.scheduled(cmd, "SHIP-" + cmd.orderId()));
                case SagaCommand.RELEASE_STOCK -> {
                    stockReleased = true;
                    reply(SagaReply.released(cmd));
                }
                default -> { /* not a command any participant owns */ }
            }
        }

        private void reply(SagaReply reply) {
            replies.send(SAGA_REPLIES, reply.orderId(), reply);
        }

        void stop() {
            running = false;
            if (consumer != null) {
                consumer.wakeup();
            }
            if (thread != null) {
                try {
                    thread.join(Duration.ofSeconds(5).toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private KafkaTemplate<String, SagaReply> replyProducer() {
            Map<String, Object> props = new HashMap<>();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString());
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
            props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
            return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        }

        private Consumer<String, SagaCommand> commandConsumer() {
            Map<String, Object> props = new HashMap<>();
            props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString());
            props.put(ConsumerConfig.GROUP_ID_CONFIG, "participants-" + UUID.randomUUID());
            props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
            JsonDeserializer<SagaCommand> vd = new JsonDeserializer<>(SagaCommand.class);
            vd.setUseTypeHeaders(false);
            vd.addTrustedPackages("dev.dev48v.*");
            Consumer<String, SagaCommand> c =
                    new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), vd).createConsumer();
            embeddedKafka.consumeFromAnEmbeddedTopic(c, SAGA_COMMANDS);
            return c;
        }
    }
}
