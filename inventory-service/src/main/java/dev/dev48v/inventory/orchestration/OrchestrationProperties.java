package dev.dev48v.inventory.orchestration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Day 29 — the knobs for inventory-service's ORCHESTRATION command handler, bound onto an immutable record with
// @ConfigurationProperties(prefix = "inventory.orchestration"). This is the ADDITIVE, opt-in counterpart to the
// Day-26/28 choreography consumer (inventory.events): with orchestration enabled, inventory-service ALSO listens
// on the shared saga-commands topic and executes the RESERVE_STOCK / RELEASE_STOCK commands the orchestrator
// sends, replying on saga-replies.
//
//   • enabled           — master switch for the command handler's @KafkaListener autoStartup AND the reply
//                         publisher. DEFAULTS OFF so the service boots exactly as before unless orchestration is
//                         switched on (the choreography path stays the shipped default).
//   • commands-topic    — SUBSCRIBE for the orchestrator's commands. MUST match order-service's
//                         orderhub.orchestration.commands-topic.
//   • replies-topic     — PUBLISH replies here. MUST match order-service's orderhub.orchestration.replies-topic.
//   • consumer-group-id — inventory-service's OWN command-consumer group, distinct from the orchestrator's and
//                         the other participants', so every service gets its own copy of every command.
@ConfigurationProperties(prefix = "inventory.orchestration")
public record OrchestrationProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("saga-commands") String commandsTopic,
        @DefaultValue("saga-replies") String repliesTopic,
        @DefaultValue("inventory-orchestration") String consumerGroupId
) {
}
