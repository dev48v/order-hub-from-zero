package dev.dev48v.orderhub.orchestration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Day 29 — the knobs for the ORCHESTRATION saga, bound type-safely onto an immutable record with
// @ConfigurationProperties(prefix = "orderhub.orchestration") — the same pattern as OrderEventProperties and
// SagaEventProperties. The orchestrator is a SECOND, alternative coordination style living beside Day 28's
// choreography, so the master switch below is what lets both patterns COEXIST without colliding:
//
//   • enabled — master switch driving the orchestrator's two @KafkaListeners' autoStartup AND the command
//               publisher. It DEFAULTS TO FALSE: choreography (orderhub.saga.enabled=true) is the shipped
//               default, and the orchestrator stays dormant — its listener containers never start, so the app
//               boots exactly as before. Flip this on (and choreography off) to drive the same order flow via a
//               central coordinator instead. Running BOTH at once would have two components trying to ship the
//               same order, so they are mutually exclusive by convention, enforced by these switches.
//   • orderPlacedTopic — the topic the orchestrator SUBSCRIBES to for OrderPlaced (the saga's trigger). Same
//                        topic order-service publishes to; the orchestrator joins in its OWN consumer group.
//   • commandsTopic    — the topic the orchestrator PUBLISHES commands to (saga-commands). Every participant
//                        subscribes to it (each in its own group) and handles the command kinds it owns.
//   • repliesTopic     — the topic participants publish replies to (saga-replies); the orchestrator is the sole
//                        consumer and drives its state machine off them.
//   • consumerGroupId  — the orchestrator's OWN consumer group for BOTH its subscriptions (order-placed +
//                        saga-replies), distinct from every participant's group.
//
// @DefaultValue gives each field a safe fallback so the record always constructs even if the keys are absent.
// Registered via @EnableConfigurationProperties(OrchestrationProperties.class) on OrchestrationKafkaConfig.
@ConfigurationProperties(prefix = "orderhub.orchestration")
public record OrchestrationProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("order-placed") String orderPlacedTopic,
        @DefaultValue("saga-commands") String commandsTopic,
        @DefaultValue("saga-replies") String repliesTopic,
        @DefaultValue("order-orchestrator") String consumerGroupId
) {
}
