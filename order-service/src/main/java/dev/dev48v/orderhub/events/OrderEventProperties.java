package dev.dev48v.orderhub.events;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Day 25 — the knobs for event publishing, bound type-safely onto an immutable record with
// @ConfigurationProperties(prefix = "orderhub.events") — the same pattern as OrderProperties,
// RateLimitProperties and ServiceAuthProperties from earlier days.
//
//   • enabled — a master switch. Publishing is ADDITIVE to the existing create-order flow: with it on,
//     placeOrder emits an OrderPlaced after saving; with it off, the flow behaves exactly as it did on
//     Day 24. The integration tests that place orders but aren't exercising Kafka flip this off so they
//     stay hermetic and never touch a broker.
//   • orderPlacedTopic — the topic name the OrderPlaced event is published to. Externalized so it can be
//     renamed per environment without a recompile; defaults to "order-placed".
//
// @DefaultValue gives each field a safe fallback so the record always constructs even if the keys are
// absent. Registered via @EnableConfigurationProperties(OrderEventProperties.class) on KafkaProducerConfig.
@ConfigurationProperties(prefix = "orderhub.events")
public record OrderEventProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("order-placed") String orderPlacedTopic
) {}
