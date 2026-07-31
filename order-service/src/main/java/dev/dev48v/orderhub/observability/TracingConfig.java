package dev.dev48v.orderhub.observability;

import brave.baggage.BaggageField;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Day 39 — DISTRIBUTED TRACING (Phase 5 · observability, the third pillar after Day-37 metrics and Day-38
// dashboards). GATED exactly like the Day-34/35/36 security flags and the Day-37 observability flag: the whole
// config only exists when orderhub.observability.tracing.enabled=true, so with the flag off (the default) none
// of its beans are created and behaviour is byte-for-byte what it was — every prior test stays green.
//
// WHAT actually turns tracing on: the bulk of Micrometer Tracing is Spring Boot AUTO-configuration (the Brave
// Tracer, the HTTP/RestTemplate/WebClient/Feign + Kafka context propagation, the trace-id/span-id → MDC
// correlation, the Zipkin reporter). That auto-config is governed by management.tracing.enabled, which
// application.yml binds to THIS same flag (${orderhub.observability.tracing.enabled:false}) — so one property
// is the single switch for the whole feature. This class adds the small amount that is genuinely OUR domain
// customization on top of the auto-config.
//
// THE BEAN: an application BAGGAGE field, "orderId". Baggage is user-defined key/value data that rides along
// WITH the trace context across every service hop (propagated on the wire next to the trace-id) and, because
// application.yml lists it under management.tracing.baggage.correlation.fields, is also copied into the logging
// MDC. So once a request stamps the orderId into baggage, every downstream service's spans AND every log line —
// here and in inventory/payment/shipping — can be filtered by that orderId, and in Grafana a Loki log line's
// traceId links straight to the matching trace. It is created as a @Bean (not a @Component) precisely so this
// @ConditionalOnProperty gate governs its existence.
@Configuration
@ConditionalOnProperty(prefix = "orderhub.observability.tracing", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ObservabilityProperties.class)
public class TracingConfig {

    // The baggage key we propagate end-to-end. Kept as a constant so producers (a controller/filter that sets it)
    // and this declaration can't drift.
    public static final String ORDER_ID_FIELD = "orderId";

    // Declare the "orderId" baggage field. Registering it as a Brave BaggageField bean makes Boot's Brave
    // auto-config include it in the propagation format, so the value set on one service travels with the trace
    // to the next and (via the correlation config in application.yml) lands in the MDC for the log pattern.
    @Bean
    public BaggageField orderIdBaggageField() {
        return BaggageField.create(ORDER_ID_FIELD);
    }
}
