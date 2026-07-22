package dev.dev48v.payment.events;

import dev.dev48v.payment.payment.Payment;
import dev.dev48v.payment.payment.PaymentLedger;
import dev.dev48v.payment.payment.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

// Day 27 — the heart of the event-driven Payment service. It CONSUMES OrderPlaced (like inventory-service's
// listener) and, crucially, ANSWERS with an event: after deciding the payment it PUBLISHES a PaymentProcessed
// to payment-events. That consume-decide-emit shape is what a full event-driven service looks like, and it is
// the seam Day 28's choreography saga builds on (inventory reserved + payment approved -> ship).
//
// Two production-shaping properties, same as Day 26:
//   • IDEMPOTENT — Kafka delivers AT-LEAST-ONCE, so the same OrderPlaced can arrive twice. For payments a
//     double-process means a DOUBLE CHARGE, so we claim the order id (PaymentLedger.claim) and process each
//     order exactly once; a redelivery is skipped before any decision is made and before any result is emitted.
//   • NON-CRASHING — the decision is a pure, deterministic function that does not throw on business input, and
//     publishing the result swallows its own failures, so a poison record can't wedge the partition.
@Component
public class OrderPlacedListener {

    private static final Logger log = LoggerFactory.getLogger(OrderPlacedListener.class);

    private final PaymentService paymentService;
    private final PaymentLedger ledger;
    private final PaymentResultPublisher publisher;

    public OrderPlacedListener(PaymentService paymentService, PaymentLedger ledger,
                               PaymentResultPublisher publisher) {
        this.paymentService = paymentService;
        this.ledger = ledger;
        this.publisher = publisher;
    }

    // Subscribe to the order-placed topic. topics/groupId resolve from payment.events.* (with literal defaults)
    // so they retune per environment without a recompile, and match what order-service publishes to.
    // containerFactory names the JSON-typed factory from KafkaConsumerConfig. autoStartup is bound to the
    // enabled switch: when payment.events.enabled=false the container never starts, so the service boots without
    // touching a broker — exactly how the non-Kafka smoke test stays hermetic.
    @KafkaListener(
            topics = "${payment.events.order-placed-topic:order-placed}",
            groupId = "${payment.events.consumer-group-id:payment-service}",
            containerFactory = "orderEventListenerContainerFactory",
            autoStartup = "${payment.events.enabled:true}")
    public void onOrderPlaced(OrderPlacedEvent event) {
        String orderId = event.orderId();

        // IDEMPOTENCY: claim the order id atomically. If it was already handled (a redelivery), skip —
        // processing again would charge the same order twice AND emit a second, contradictory result.
        if (!ledger.claim(orderId)) {
            log.info("Duplicate OrderPlaced for order {} (event {}) - already charged, skipping",
                    orderId, event.eventId());
            return;
        }

        try {
            // Derive the charge amount from the order line, then decide (approve/decline). Deterministic — no
            // network, no business exception — so the listener never loops on a record. A DECLINE is a normal
            // business OUTCOME emitted as a PaymentProcessed event below, NOT an error: it is never thrown and
            // so never retried or dead-lettered.
            BigDecimal amount = paymentService.amountFor(event.quantity());
            Payment payment = paymentService.process(orderId, event.customer(), amount);
            ledger.record(payment);

            // ANSWER with an event: publish the result so downstream reactions (Day 28's saga, notifications,
            // the order's status projection) can consume it. Publishing failures are swallowed in the publisher.
            PaymentProcessedEvent result = PaymentProcessedEvent.from(payment, event.eventId());
            publisher.publish(result);

            log.info("Payment {} for order {} ({} {}) - reason {}",
                    payment.getStatus(), orderId, amount, payment.getReason(), result.eventId());
        } catch (RuntimeException ex) {
            // Day 31 — a TECHNICAL/unexpected failure (a gateway/serialization/bug fault, NOT a decline).
            // Release the idempotency claim so the retry re-processes, then RETHROW so the DefaultErrorHandler
            // retries with backoff and, once exhausted, routes the record to order-placed.DLT instead of
            // swallowing it or looping the partition.
            ledger.unclaim(orderId);
            throw ex;
        }
    }
}
