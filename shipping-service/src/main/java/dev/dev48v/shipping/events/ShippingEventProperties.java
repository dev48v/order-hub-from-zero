package dev.dev48v.shipping.events;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Day 28 — the knobs for shipping-service, bound type-safely onto an immutable record with
// @ConfigurationProperties(prefix = "shipping.events") — the mirror of the other services' *EventProperties.
// A choreography participant needs to know exactly which channels it reads from and writes to, and nothing
// more; there is no orchestrator to configure, only topics:
//
//   • enabled             — a master switch driving the @KafkaListener's autoStartup AND the event publisher.
//                           On, the service consumes PaymentProcessed and emits its next event; off, the
//                           listener container never starts and nothing is published, so the app boots
//                           without touching a broker (how the non-Kafka smoke test stays hermetic).
//   • paymentEventsTopic  — the topic to SUBSCRIBE to: payment-service's PaymentProcessed results. Must match
//                           payment-service's payment-events-topic.
//   • shipmentEventsTopic — the topic the HAPPY-path fact is published to (shipping-events): ShipmentScheduled,
//                           i.e. "the order is confirmed and shipped". Any downstream (order-service's status
//                           projection, a notification service) can subscribe.
//   • orderCancelledTopic — the topic the COMPENSATING fact is published to (order-cancelled): OrderCancelled.
//                           inventory-service consumes it to RELEASE the reserved stock; it also tells the
//                           world the order was cancelled. This is the saga's compensation channel.
//   • consumerGroupId     — the Kafka consumer group shipping-service joins. Its OWN group, distinct from
//                           inventory-service's and payment-service's, so it gets its own independent copy of
//                           every PaymentProcessed.
//
// @DefaultValue gives each field a safe fallback so the record always constructs even if the keys are absent.
// Registered via @EnableConfigurationProperties(ShippingEventProperties.class) on KafkaConsumerConfig.
@ConfigurationProperties(prefix = "shipping.events")
public record ShippingEventProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("payment-events") String paymentEventsTopic,
        @DefaultValue("shipping-events") String shipmentEventsTopic,
        @DefaultValue("order-cancelled") String orderCancelledTopic,
        @DefaultValue("shipping-service") String consumerGroupId
) {
}
