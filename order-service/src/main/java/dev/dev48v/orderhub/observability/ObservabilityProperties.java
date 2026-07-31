package dev.dev48v.orderhub.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Day 37 — externalised switch for the OBSERVABILITY feature, bound type-safely from
// "orderhub.observability.*". Same discipline as the Day-34/35/36 security flags: a new production
// concern must never change what already works until it is explicitly switched on.
//
//   metrics.enabled — master switch for the CUSTOM order-flow meters, DEFAULT FALSE. When off, the
//                     OrderMetrics bean is never created (ObservabilityConfig is @ConditionalOnProperty),
//                     so OrderService's ObjectProvider yields nothing and the service records nothing —
//                     behaviour is byte-for-byte what it was before, which is why every prior test stays
//                     green. When on, OrderMetrics registers a Counter (orders.placed, tagged by outcome),
//                     a Timer (order.processing) and a Gauge (orders.open) against the shared MeterRegistry,
//                     and OrderService feeds them from the real create/confirm paths.
//
// Note this gates only OUR domain meters. The /actuator/prometheus endpoint (and the JVM/HTTP/Resilience4j
// meters actuator + micrometer already provide) is governed by management.endpoints.web.exposure.include —
// a passive, pull-based scrape surface that is safe to expose regardless of this flag.
//
//   tracing.enabled — Day 39 master switch for DISTRIBUTED TRACING (the third observability pillar), DEFAULT
//                     FALSE. It is the SINGLE gate: application.yml binds management.tracing.enabled to this
//                     value (${orderhub.observability.tracing.enabled:false}), so with it off Boot's Brave/Zipkin
//                     tracing auto-configuration never activates — no trace context, no span reporting — and
//                     behaviour is byte-for-byte what it was, which keeps every prior test green. TracingConfig
//                     (@ConditionalOnProperty on this flag) then adds our own tracing beans only when it is on.
//   tracing.loki    — the Loki log-aggregation settings. The Loki logback appender is activated by the `loki`
//                     Spring profile (see logback-spring.xml); these bound values document the shipping target
//                     and are the type-safe mirror of the properties logback reads from the Environment.
@ConfigurationProperties(prefix = "orderhub.observability")
public record ObservabilityProperties(
        @DefaultValue Metrics metrics,
        @DefaultValue Tracing tracing
) {
    public record Metrics(
            @DefaultValue("false") boolean enabled
    ) {
    }

    public record Tracing(
            @DefaultValue("false") boolean enabled,
            @DefaultValue Loki loki
    ) {
        public record Loki(
                @DefaultValue("false") boolean enabled,
                @DefaultValue("http://localhost:3100/loki/api/v1/push") String url,
                @DefaultValue("order-service") String appLabel
        ) {
        }
    }
}
