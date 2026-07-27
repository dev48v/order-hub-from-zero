package dev.dev48v.orderhub.observability;

import dev.dev48v.orderhub.domain.Order;
import dev.dev48v.orderhub.inventory.InventoryServiceClient;
import dev.dev48v.orderhub.inventory.ReserveRequest;
import dev.dev48v.orderhub.inventory.StockView;
import dev.dev48v.orderhub.service.OrderService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

// Day 37 — the observability integration test. It boots the FULL app context with the observability flag ON
// (orderhub.observability.metrics.enabled=true), so ObservabilityConfig creates the OrderMetrics bean and
// OrderService is wired to it. Two things are proven:
//   1) the custom meters (Counter orders.placed, Timer order.processing, Gauge orders.open) are REGISTERED
//      against the shared MeterRegistry and actually INCREMENT / RECORD when real order operations run;
//   2) /actuator/prometheus responds and renders those meters in Prometheus text format when enabled.
//
// It runs under plain `mvn test` (no Docker): the datasource is in-memory H2 (Flyway migrates it), the Feign
// inventory client is a @MockBean so no discovery/registry is needed, Kafka/eureka/saga are switched off, and
// a @Primary in-memory CacheManager replaces the Redis-backed one so the cached service methods run without a
// live Redis. Every OTHER test keeps observability OFF (the default), so the OrderMetrics bean doesn't exist
// for them and their behaviour is unchanged — this is the only place the meters are active.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "orderhub.observability.metrics.enabled=true",
                "management.endpoints.web.exposure.include=health,prometheus,metrics",
                // Boot the full context offline: no registry, no broker, no Redis-dependent filters.
                "eureka.client.enabled=false",
                "spring.cloud.discovery.enabled=false",
                "orderhub.events.enabled=false",
                "orderhub.saga.enabled=false",
                "app.ratelimit.enabled=false",
                "app.idempotency.enabled=false"
        })
// Spring Boot DISABLES metrics-export registries (Prometheus included) inside @SpringBootTest by default, so a
// naked test only gets a SimpleMeterRegistry and /actuator/prometheus has nothing to scrape. @AutoConfigureObservability
// re-enables the metrics export path so the PrometheusMeterRegistry + /actuator/prometheus endpoint are wired,
// exactly as they are at runtime. tracing=false — distributed tracing is Day 40, not part of this test.
@AutoConfigureObservability(tracing = false)
@DisplayName("Day 37 · Micrometer + Prometheus observability metrics")
class ObservabilityMetricsTest {

    // Swap the Redis-backed RedisCacheManager for an in-memory one so @Cacheable/@CacheEvict on the order
    // service run without a live Redis. @Primary makes it win; the Redis bean stays defined but unused.
    @TestConfiguration
    static class InMemoryCacheConfig {
        @Bean
        @Primary
        CacheManager testCacheManager() {
            return new ConcurrentMapCacheManager("order", "orders");
        }
    }

    // Replace the load-balanced Feign proxy so no discovery/registry is needed and we dictate reservations.
    @MockBean
    private InventoryServiceClient inventory;

    @Autowired
    private OrderService orderService;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("custom meters are registered and increment/record when orders are placed and confirmed")
    void metersRecordOnOrderOperations() {
        when(inventory.reserve(eq("Keyboard"), any(ReserveRequest.class)))
                .thenReturn(new StockView("Keyboard", "Mechanical keyboard", 40, true));

        // Snapshot the cumulative meters first, so the assertions are independent of any other test's activity
        // against the SAME (shared-context) registry.
        double successBefore = successCount();
        long timerBefore = registry.get(OrderMetrics.PROCESSING_TIMER).timer().count();
        double openBefore = registry.get(OrderMetrics.OPEN_GAUGE).gauge().value();

        Order first = orderService.placeOrder("Ada", "Keyboard", 2);
        orderService.placeOrder("Grace", "Keyboard", 1);
        orderService.confirmOrder(first.getId());

        // Counter: two successful placements were recorded under outcome=success.
        assertThat(successCount() - successBefore)
                .as("orders.placed{outcome=success} incremented twice")
                .isEqualTo(2.0);

        // Timer: recorded once per placement (2), regardless of outcome.
        assertThat(registry.get(OrderMetrics.PROCESSING_TIMER).timer().count() - timerBefore)
                .as("order.processing timer recorded both placements")
                .isEqualTo(2L);

        // Gauge: 2 placed, 1 confirmed -> net +1 still open / in flight.
        assertThat(registry.get(OrderMetrics.OPEN_GAUGE).gauge().value() - openBefore)
                .as("orders.open reflects 2 placed minus 1 confirmed")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("a rejected placement increments the counter under outcome=rejected")
    void rejectedPlacementIsTagged() {
        double before = registry.get(OrderMetrics.PLACED_COUNTER)
                .tag("outcome", OrderMetrics.OUTCOME_REJECTED).counter().count();

        // 100000 is far above app.orders.max-quantity (1000) — rejected by the business guard before any save.
        assertThatThrownBy(() -> orderService.placeOrder("Ada", "Pallet", 100000))
                .isInstanceOf(IllegalArgumentException.class);

        double after = registry.get(OrderMetrics.PLACED_COUNTER)
                .tag("outcome", OrderMetrics.OUTCOME_REJECTED).counter().count();
        assertThat(after - before).isEqualTo(1.0);
    }

    @Test
    @DisplayName("/actuator/prometheus responds in Prometheus text format and exposes the custom meters")
    void prometheusEndpointExposesCustomMeters() {
        ResponseEntity<String> resp = rest.getForEntity("/actuator/prometheus", String.class);

        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("GET /actuator/prometheus -> %s | body: %s", resp.getStatusCode(),
                        resp.getBody() == null ? "null"
                                : resp.getBody().substring(0, Math.min(300, resp.getBody().length())))
                .isTrue();
        String body = resp.getBody();
        assertThat(body).isNotNull();
        // The custom meters are pre-registered at startup, so the series exist even before any order is placed.
        assertThat(body).contains("orders_placed_total");        // Counter -> _total suffix, tagged by outcome
        assertThat(body).contains("order_processing_seconds");   // Timer -> seconds base unit
        assertThat(body).contains("orders_open");                // Gauge
    }

    private double successCount() {
        return registry.get(OrderMetrics.PLACED_COUNTER)
                .tag("outcome", OrderMetrics.OUTCOME_SUCCESS).counter().count();
    }
}
