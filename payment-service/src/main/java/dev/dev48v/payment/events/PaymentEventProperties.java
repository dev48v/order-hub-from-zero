package dev.dev48v.payment.events;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

// Day 27 — the knobs for payment-service, bound type-safely onto an immutable record with
// @ConfigurationProperties(prefix = "payment.events") — the mirror of order-service's OrderEventProperties
// and inventory-service's InventoryEventProperties. Two groups of settings live here:
//
//   EVENT ROUTING (same shape as the other services):
//   • enabled            — a master switch driving the @KafkaListener's autoStartup AND the result publisher.
//                          On, the service consumes OrderPlaced and emits PaymentProcessed; off, the listener
//                          container never starts and nothing is published, so the app boots without a broker
//                          (how the non-Kafka smoke test stays hermetic).
//   • orderPlacedTopic   — the topic to SUBSCRIBE to. Must match order-service's order-placed topic.
//   • paymentEventsTopic — the NEW topic this service PUBLISHES its result to (payment-events). Day 28's
//                          saga consumes it; today we just prove it is emitted.
//   • consumerGroupId    — the Kafka consumer group payment-service joins. It is DIFFERENT from
//                          inventory-service's group, which is the whole point: each service gets its OWN
//                          independent copy of every OrderPlaced (groups don't steal messages from each other).
//
//   PAYMENT SIMULATION (deterministic, so tests are repeatable — a real gateway call would replace this):
//   • unitPrice          — a flat per-unit price used to turn an order's quantity into a charge AMOUNT
//                          (OrderPlaced carries no money field, so we derive one deterministically).
//   • declineThreshold   — orders whose amount EXCEEDS this are DECLINED ("over-limit"), everything else is
//                          approved. Makes the approve/decline split a pure function of the event.
//   • declineCustomer    — a "test card": an order from this customer name is always DECLINED, so the decline
//                          path is trivially reproducible regardless of amount.
//
//   RETRY / DEAD-LETTER (Day 31):
//   • retryAttempts      — how many EXTRA deliveries a failing record gets after the first before it is routed
//                          to the dead-letter topic. Total attempts = 1 + retryAttempts.
//   • retryBackoffMs     — how long (ms) the error handler waits between those retry attempts.
//
// @DefaultValue gives each field a safe fallback so the record always constructs even if the keys are absent.
// Registered via @EnableConfigurationProperties(PaymentEventProperties.class) on KafkaConsumerConfig.
@ConfigurationProperties(prefix = "payment.events")
public record PaymentEventProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("order-placed") String orderPlacedTopic,
        @DefaultValue("payment-events") String paymentEventsTopic,
        @DefaultValue("payment-service") String consumerGroupId,
        @DefaultValue("100.00") BigDecimal unitPrice,
        @DefaultValue("1000.00") BigDecimal declineThreshold,
        @DefaultValue("DECLINE") String declineCustomer,
        @DefaultValue("3") int retryAttempts,
        @DefaultValue("500") long retryBackoffMs
) {
}
