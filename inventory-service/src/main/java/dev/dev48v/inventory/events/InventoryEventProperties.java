package dev.dev48v.inventory.events;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Day 26 — the knobs for event CONSUMPTION, bound type-safely onto an immutable record with
// @ConfigurationProperties(prefix = "inventory.events") — the mirror image of order-service's
// OrderEventProperties, and the same pattern used for OrderProperties/ServiceAuthProperties earlier.
//
//   • enabled — a master switch that drives the listener's autoStartup. With it on, inventory-service
//     starts a Kafka consumer and reserves stock on every OrderPlaced. With it off, the @KafkaListener
//     container simply never starts, so the service boots without reaching for a broker at all — which
//     is how the non-Kafka tests (InventoryServiceApplicationTests) stay hermetic.
//   • orderPlacedTopic — the topic to subscribe to. Externalized so it can be renamed per environment
//     without a recompile; must match the topic order-service publishes to (default "order-placed").
//   • consumerGroupId — the Kafka CONSUMER GROUP this service joins. All instances of inventory-service
//     share ONE group, so Kafka spreads the topic's partitions across them and each event is handled by
//     exactly one instance (scale out = more instances in the same group). A DIFFERENT service that also
//     wants OrderPlaced (payments, Day 27) uses its OWN group id and gets its own independent copy.
//
// @DefaultValue gives each field a safe fallback so the record always constructs even if the keys are
// absent. Registered via @EnableConfigurationProperties(InventoryEventProperties.class) on KafkaConsumerConfig.
@ConfigurationProperties(prefix = "inventory.events")
public record InventoryEventProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("order-placed") String orderPlacedTopic,
        @DefaultValue("inventory-service") String consumerGroupId
) {
}
