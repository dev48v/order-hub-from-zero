package dev.dev48v.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.FilterDefinition;
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Day 20 — assert the ROUTE TABLE the gateway actually loaded, without any network.
//
// The value of the gateway is its routing config, so we test that config directly: read the parsed
// route definitions from the RouteDefinitionLocator (the same objects the gateway routes against at
// runtime) and assert each public path maps to the right service, by name, over lb://. This never
// contacts Eureka or a downstream service — lb:// is resolved per REQUEST, and we make no request; we
// only inspect the loaded table. Eureka is disabled so the context boots offline in the reactor build.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.port=0",
                "eureka.client.enabled=false",
                "eureka.client.register-with-eureka=false",
                "eureka.client.fetch-registry=false"
        })
@DisplayName("the gateway route table maps /api paths to services by name over lb://")
class GatewayRouteConfigTest {

    @Autowired
    RouteDefinitionLocator routeDefinitionLocator;

    private List<RouteDefinition> routes() {
        // The locator returns a reactive stream of definitions; block to collect them for assertions.
        return routeDefinitionLocator.getRouteDefinitions().collectList().block();
    }

    private RouteDefinition routeById(String id) {
        return routes().stream()
                .filter(r -> id.equals(r.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no route defined with id '" + id + "'"));
    }

    @Test
    @DisplayName("/api/orders/** routes to lb://order-service")
    void ordersRouteResolvesOrderServiceByName() {
        RouteDefinition orders = routeById("order-service");

        // Discovery-driven target: the name, not a host:port. lb:// is what triggers load-balanced
        // resolution against the Eureka registry.
        assertThat(orders.getUri()).hasToString("lb://order-service");

        // A Path predicate matching the public orders prefix.
        PredicateDefinition path = orders.getPredicates().stream()
                .filter(p -> "Path".equals(p.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("order-service route has no Path predicate"));
        assertThat(path.getArgs().values()).contains("/api/orders/**");

        // The per-route pre-filter that stamps the edge header is present.
        assertThat(orders.getFilters())
                .extracting(FilterDefinition::getName)
                .contains("AddRequestHeader");
    }

    @Test
    @DisplayName("/api/inventory/** routes to lb://inventory-service")
    void inventoryRouteResolvesInventoryServiceByName() {
        RouteDefinition inventory = routeById("inventory-service");

        assertThat(inventory.getUri()).hasToString("lb://inventory-service");

        PredicateDefinition path = inventory.getPredicates().stream()
                .filter(p -> "Path".equals(p.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("inventory-service route has no Path predicate"));
        assertThat(path.getArgs().values()).contains("/api/inventory/**");
    }

    @Test
    @DisplayName("both public routes are discovery-driven (every uri is an lb:// name, no hardcoded host)")
    void everyRouteIsDiscoveryDriven() {
        assertThat(routes())
                .isNotEmpty()
                .allSatisfy(r -> assertThat(r.getUri().getScheme()).isEqualTo("lb"));
    }
}
