package dev.dev48v.inventory.events;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Day 26 — the knobs for event CONSUMPTION, bound type-safely onto an immutable record with
// @ConfigurationProperties(prefix = "inventory.events") — the mirror image of order-service's
// OrderEventProperties, and the same pattern used for OrderProperties/ServiceAuthProperties earlier.
//
//   • enabled — a master switch that drives BOTH @KafkaListeners' autoStartup AND the StockReserved publisher.
//     With it on, inventory-service consumes OrderPlaced (reserving + emitting StockReserved) and OrderCancelled
//     (releasing). With it off, neither container starts and nothing is published, so the service boots without
//     reaching for a broker at all — which is how the non-Kafka tests (InventoryServiceApplicationTests) stay
//     hermetic.
//   • orderPlacedTopic — the topic to SUBSCRIBE to for OrderPlaced. Externalized so it can be renamed per
//     environment without a recompile; must match the topic order-service publishes to (default "order-placed").
//   • stockEventsTopic — Day 28: the topic inventory-service now PUBLISHES its StockReserved result to. The
//     order saga subscribes to it; must match order-service's orderhub.saga.stock-events-topic (default
//     "inventory-events").
//   • orderCancelledTopic — Day 28: the topic to SUBSCRIBE to for the saga's compensating OrderCancelled. On
//     each one inventory-service releases the stock it reserved; must match order-service's
//     orderhub.saga.order-cancelled-topic (default "order-cancelled").
//   • consumerGroupId — the Kafka CONSUMER GROUP this service joins. All instances of inventory-service
//     share ONE group, so Kafka spreads each topic's partitions across them and each event is handled by
//     exactly one instance (scale out = more instances in the same group). A DIFFERENT service that also
//     wants OrderPlaced (payments, Day 27) uses its OWN group id and gets its own independent copy.
//
// @DefaultValue gives each field a safe fallback so the record always constructs even if the keys are
// absent. Registered via @EnableConfigurationProperties(InventoryEventProperties.class) on KafkaConsumerConfig.
@ConfigurationProperties(prefix = "inventory.events")
public record InventoryEventProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("order-placed") String orderPlacedTopic,
        @DefaultValue("inventory-events") String stockEventsTopic,
        @DefaultValue("order-cancelled") String orderCancelledTopic,
        @DefaultValue("inventory-service") String consumerGroupId
) {
}
