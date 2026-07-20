package dev.dev48v.shipping.orchestration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Day 29 — the knobs for shipping-service's ORCHESTRATION command handler, bound onto an immutable record with
// @ConfigurationProperties(prefix = "shipping.orchestration"). ADDITIVE + opt-in beside the Day-28 choreography
// listener (shipping.events): with orchestration enabled, shipping-service ALSO listens on saga-commands and
// runs the SCHEDULE_SHIPMENT command the orchestrator sends, replying on saga-replies. DEFAULTS OFF so the
// service boots exactly as before (shipping-service has no application.yml, so these defaults are the whole
// config until an env var overrides them). The two coordination styles are mutually exclusive.
@ConfigurationProperties(prefix = "shipping.orchestration")
public record OrchestrationProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("saga-commands") String commandsTopic,
        @DefaultValue("saga-replies") String repliesTopic,
        @DefaultValue("shipping-orchestration") String consumerGroupId
) {
}
