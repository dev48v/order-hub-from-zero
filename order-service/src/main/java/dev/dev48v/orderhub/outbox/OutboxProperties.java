package dev.dev48v.orderhub.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Day 30 — the knobs for the TRANSACTIONAL OUTBOX, bound type-safely onto an immutable record with
// @ConfigurationProperties(prefix = "orderhub.outbox") — the same pattern as OrderEventProperties (Day 25),
// SagaEventProperties (Day 28) and OrchestrationProperties (Day 29).
//
// The outbox is a REPLACEMENT for Day 25's direct, fire-and-forget publish, not an addition to it. When it's
// ON, placeOrder writes the OrderPlaced event into the outbox_event table IN THE SAME TRANSACTION as the order
// (so state + the intent-to-publish commit atomically) and a background relay does the actual Kafka publish;
// the direct publish is skipped. When it's OFF (the DEFAULT), nothing changes — placeOrder publishes directly
// exactly as it did on Day 25, and the relay stays inert. This gate is what keeps the whole feature
// non-breaking: every earlier test that isn't about the outbox runs with it disabled and behaves identically.
//
//   • enabled       — master switch for the same-tx outbox WRITE in placeOrder (default false).
//   • topic         — the Kafka topic the relay publishes each row to; must match the OrderPlaced topic other
//                     services subscribe to (Day 25/29 = "order-placed").
//   • aggregateType — the aggregate an outbox row is about, stored on the row for observability/routing ("Order").
//   • relayEnabled  — whether the @Scheduled relay POLLS for unsent rows (default true). Turned off in the test
//                     that needs to observe a written-but-unpublished row before driving the relay by hand.
//   • relayBatchSize — how many unsent rows the relay drains per poll, oldest-first (a bound so one tick can't
//                     pull an unbounded backlog).
//
// @DefaultValue gives each field a safe fallback so the record always constructs even if the keys are absent.
// Registered via @EnableConfigurationProperties(OutboxProperties.class) on OutboxKafkaConfig.
@ConfigurationProperties(prefix = "orderhub.outbox")
public record OutboxProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("order-placed") String topic,
        @DefaultValue("Order") String aggregateType,
        @DefaultValue("true") boolean relayEnabled,
        @DefaultValue("200") int relayBatchSize
) {}
