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
@ConfigurationProperties(prefix = "orderhub.observability")
public record ObservabilityProperties(
        @DefaultValue Metrics metrics
) {
    public record Metrics(
            @DefaultValue("false") boolean enabled
    ) {
    }
}
