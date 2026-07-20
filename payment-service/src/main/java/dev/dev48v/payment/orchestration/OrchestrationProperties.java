package dev.dev48v.payment.orchestration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Day 29 — the knobs for payment-service's ORCHESTRATION command handler, bound onto an immutable record with
// @ConfigurationProperties(prefix = "payment.orchestration"). ADDITIVE + opt-in beside the Day-27 choreography
// consumer (payment.events): with orchestration enabled, payment-service ALSO listens on saga-commands and runs
// the PROCESS_PAYMENT command the orchestrator sends, replying on saga-replies. DEFAULTS OFF so the service
// boots exactly as before unless orchestration is switched on.
@ConfigurationProperties(prefix = "payment.orchestration")
public record OrchestrationProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("saga-commands") String commandsTopic,
        @DefaultValue("saga-replies") String repliesTopic,
        @DefaultValue("payment-orchestration") String consumerGroupId
) {
}
