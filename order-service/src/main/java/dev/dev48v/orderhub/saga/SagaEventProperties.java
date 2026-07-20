package dev.dev48v.orderhub.saga;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Day 28 — the knobs for the choreography SAGA, bound type-safely onto an immutable record with
// @ConfigurationProperties(prefix = "orderhub.saga") — the same pattern as OrderEventProperties (Day 25)
// and every other *Properties in the project. The saga is the order service's REACTIVE half: where Day 25's
// OrderEventProperties governs the OrderPlaced it PUBLISHES, this governs the two results it CONSUMES
// (StockReserved + PaymentProcessed) and the two terminal facts it PUBLISHES (OrderShipped / OrderCancelled).
//
//   • enabled                — master switch driving BOTH saga @KafkaListeners' autoStartup AND the result
//                              publisher. On, order-service subscribes to the two result topics and drives the
//                              saga to ship or compensate. Off, the listener containers never start and nothing
//                              is published, so the app (and the existing full-context tests) boot without a
//                              broker — exactly how order-service behaved before Day 28.
//   • stockEventsTopic       — the topic to SUBSCRIBE to for inventory's StockReserved results (Day 28 inventory
//                              producer). Must match inventory-service's inventory.events stock-events-topic.
//   • paymentEventsTopic      — the topic to SUBSCRIBE to for payment's PaymentProcessed results. Must match
//                              payment-service's payment.events payment-events-topic (Day 27).
//   • orderShippedTopic      — the NEW topic the saga PUBLISHES OrderShipped to on the happy path.
//   • orderCancelledTopic    — the NEW topic the saga PUBLISHES OrderCancelled to when it compensates;
//                              inventory-service subscribes to it to release the reserved stock.
//   • consumerGroupId        — the Kafka consumer group order-service's saga joins. Its OWN group, distinct
//                              from inventory-service's and payment-service's, so it gets its own independent
//                              copy of every result event.
//
// @DefaultValue gives each field a safe fallback so the record always constructs even if the keys are absent.
// Registered via @EnableConfigurationProperties(SagaEventProperties.class) on SagaKafkaConfig.
@ConfigurationProperties(prefix = "orderhub.saga")
public record SagaEventProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("inventory-events") String stockEventsTopic,
        @DefaultValue("payment-events") String paymentEventsTopic,
        @DefaultValue("order-shipped") String orderShippedTopic,
        @DefaultValue("order-cancelled") String orderCancelledTopic,
        @DefaultValue("order-saga") String consumerGroupId
) {
}
