package dev.dev48v.orderhub.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Day 37 — wires the custom order-flow meters, GATED exactly like the Day-34/35/36 security features. The whole
// config (and therefore the OrderMetrics bean) only exists when orderhub.observability.metrics.enabled=true, so
// with the flag off (the default) OrderMetrics is never created — OrderService's ObjectProvider<OrderMetrics>
// simply yields nothing and records nothing, and every prior test stays green.
//
// OrderMetrics takes the app's single Micrometer MeterRegistry (auto-configured by actuator; a
// PrometheusMeterRegistry once micrometer-registry-prometheus is on the classpath) and registers its Counter,
// Timer and Gauge against it — so our domain meters are exposed on /actuator/prometheus alongside the built-in
// JVM/HTTP/Resilience4j ones. It is created as a @Bean (not a @Component) precisely so this @ConditionalOnProperty
// gate governs its existence.
@Configuration
@ConditionalOnProperty(prefix = "orderhub.observability.metrics", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityConfig {

    @Bean
    public OrderMetrics orderMetrics(MeterRegistry registry) {
        return new OrderMetrics(registry);
    }
}
