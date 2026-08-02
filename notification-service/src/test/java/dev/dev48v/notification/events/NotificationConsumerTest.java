package dev.dev48v.notification.events;

import dev.dev48v.notification.config.KafkaConsumerConfig;
import dev.dev48v.notification.notify.Channel;
import dev.dev48v.notification.notify.LoggingNotificationSender;
import dev.dev48v.notification.notify.MockSmsClient;
import dev.dev48v.notification.notify.Notification;
import dev.dev48v.notification.notify.NotificationLedger;
import dev.dev48v.notification.notify.NotificationService;
import dev.dev48v.notification.notify.NotificationTemplates;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Day 41 — proves the whole event-driven notification flow end to end WITHOUT a real broker (and without SMTP):
// @EmbeddedKafka stands up a throwaway in-JVM Kafka broker for this test class, so the build needs no Docker and
// no running Kafka. We build a SLICE of the app — the REAL consumer beans (KafkaConsumerConfig), the listener,
// the dispatcher (NotificationService), the ledger, the templates, the LOG email sender (the "mock sender") and
// the mock SMS client — then PUBLISH each lifecycle event (as JSON, exactly like the real producers) and assert:
//   (1) a consumed event triggers EXACTLY ONE email notification, with the correct TEMPLATED content;
//   (2) the OPTIONAL SMS channel also fires when enabled;
//   (3) a REDELIVERED event (same eventId) is DEDUPED — no second notification.
//
// email-enabled=false makes LoggingNotificationSender the active NotificationSender (no SMTP), and the dispatcher
// records every dispatched Notification into NotificationLedger — so the ledger is the assertion surface for what
// "the mock sender" was handed. sms-enabled=true so the optional second channel is exercised. @EnableKafka lives
// on KafkaConsumerConfig so the @KafkaListeners are detected even in this sliced (no auto-config) context, and
// autoStartup is bound to orderhub.notifications.enabled=true so the containers actually run here.
@SpringBootTest(
        classes = NotificationConsumerTest.TestApp.class,
        properties = {
                "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
                "orderhub.notifications.enabled=true",
                "orderhub.notifications.email-enabled=false",
                "orderhub.notifications.sms-enabled=true",
                "orderhub.notifications.order-placed-topic=order-placed",
                "orderhub.notifications.payment-events-topic=payment-events",
                "orderhub.notifications.shipment-events-topic=shipping-events",
                "orderhub.notifications.consumer-group-id=notification-consumer-test"
        })
@EmbeddedKafka(partitions = 1, topics = {"order-placed", "payment-events", "shipping-events"})
@DisplayName("Day 41 · notification-service consumes lifecycle events and sends exactly one templated notification each")
class NotificationConsumerTest {

    private static final String ORDER_PLACED = "order-placed";
    private static final String PAYMENT_EVENTS = "payment-events";
    private static final String SHIPPING_EVENTS = "shipping-events";

    // The real consumer wiring + dispatcher + ledger + templates + the LOG email sender ("mock sender") + the
    // mock SMS client. A plain (lite) config passed as the sole classes = ... so Spring Boot keeps the context
    // SLICED and does not fall back to the full @SpringBootApplication.
    @Import({KafkaConsumerConfig.class, NotificationEventListener.class, NotificationService.class,
            NotificationLedger.class, NotificationTemplates.class, LoggingNotificationSender.class,
            MockSmsClient.class})
    static class TestApp {
    }

    @Autowired
    private NotificationLedger ledger;

    @Autowired
    private EmbeddedKafkaBroker embeddedKafka;

    // A JSON producer that mirrors the real producers (JSON value, NO type headers), so the bytes on the topic
    // are exactly what notification-service decodes in production.
    private <T> KafkaTemplate<String, T> jsonProducer() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);   // language-neutral payload, like the real producers
        ProducerFactory<String, T> pf = new DefaultKafkaProducerFactory<>(props);
        return new KafkaTemplate<>(pf);
    }

    private List<Notification> emailsFor(String orderId) {
        return ledger.forOrder(orderId).stream().filter(n -> n.channel() == Channel.EMAIL).toList();
    }

    @Test
    @DisplayName("an OrderPlaced event triggers exactly ONE templated email notification, plus the optional SMS")
    void orderPlacedSendsOneTemplatedEmailAndSms() {
        OrderPlacedEvent event = new OrderPlacedEvent(
                UUID.randomUUID().toString(), "ORD-N1", "Ada Lovelace", "KEYBOARD-001", 2,
                "PLACED", Instant.now(), Instant.now());
        this.<OrderPlacedEvent>jsonProducer().send(ORDER_PLACED, event.orderId(), event);

        // Exactly one EMAIL notification is dispatched, with the templated subject/body and derived recipient.
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(emailsFor("ORD-N1")).hasSize(1));
        Notification email = emailsFor("ORD-N1").get(0);
        assertThat(email.recipient()).isEqualTo("ada.lovelace@example.com");
        assertThat(email.subject()).isEqualTo("Order #ORD-N1 placed");
        assertThat(email.body()).contains("your order #ORD-N1 was placed", "2 x KEYBOARD-001");

        // The OPTIONAL SMS channel also fired (sms-enabled=true), to the derived phone number.
        List<Notification> sms = ledger.forOrder("ORD-N1").stream()
                .filter(n -> n.channel() == Channel.SMS).toList();
        assertThat(sms).hasSize(1);
        assertThat(sms.get(0).recipient()).startsWith("+1-555-");
    }

    @Test
    @DisplayName("PaymentProcessed and ShipmentScheduled each produce their own templated notification")
    void paymentAndShipmentEachNotify() {
        // APPROVED payment -> "your order #X was paid".
        PaymentProcessedEvent paid = new PaymentProcessedEvent(
                UUID.randomUUID().toString(), "ORD-N2", "Grace Hopper", new BigDecimal("200.00"),
                "APPROVED", "APPROVED", UUID.randomUUID().toString(), Instant.now());
        this.<PaymentProcessedEvent>jsonProducer().send(PAYMENT_EVENTS, paid.orderId(), paid);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(emailsFor("ORD-N2")).hasSize(1));
        assertThat(emailsFor("ORD-N2").get(0).body()).contains("your order #ORD-N2 was paid", "200.00");

        // ShipmentScheduled -> "your order #X was shipped", with the tracking number.
        ShipmentScheduledEvent shipped = new ShipmentScheduledEvent(
                UUID.randomUUID().toString(), "ORD-N3", "Linus Torvalds", new BigDecimal("200.00"),
                "CONFIRMED", "SHIP-ABCD1234", UUID.randomUUID().toString(), Instant.now());
        this.<ShipmentScheduledEvent>jsonProducer().send(SHIPPING_EVENTS, shipped.orderId(), shipped);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(emailsFor("ORD-N3")).hasSize(1));
        assertThat(emailsFor("ORD-N3").get(0).body()).contains("your order #ORD-N3 was shipped", "SHIP-ABCD1234");
    }

    @Test
    @DisplayName("a duplicate event (same eventId) is NOT notified twice — idempotent dedup on redelivery")
    void duplicateEventIsNotNotifiedTwice() {
        OrderPlacedEvent dup = new OrderPlacedEvent(
                UUID.randomUUID().toString(), "ORD-N4", "Alan Turing", "MOUSE-001", 3,
                "PLACED", Instant.now(), Instant.now());
        KafkaTemplate<String, OrderPlacedEvent> producer = jsonProducer();
        producer.send(ORDER_PLACED, dup.orderId(), dup);
        producer.send(ORDER_PLACED, dup.orderId(), dup);   // AT-LEAST-ONCE redelivery of the SAME event (same eventId)

        // A sentinel on a different order. With a single partition, records are consumed in send order, so once
        // the sentinel is handled we KNOW both duplicate deliveries were already processed.
        OrderPlacedEvent sentinel = new OrderPlacedEvent(
                UUID.randomUUID().toString(), "ORD-N4-SENTINEL", "Ada Lovelace", "HUB-001", 1,
                "PLACED", Instant.now(), Instant.now());
        producer.send(ORDER_PLACED, sentinel.orderId(), sentinel);

        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(emailsFor("ORD-N4-SENTINEL")).hasSize(1));

        // Despite two deliveries of the same event, EXACTLY ONE email notification was dispatched for ORD-N4.
        assertThat(emailsFor("ORD-N4")).hasSize(1);
    }
}
