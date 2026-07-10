package dev.dev48v.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Day 20 — smoke test that the gateway is a real, bootable WebFlux/Netty app.
// Standing up Spring Cloud Gateway pulls in a lot of auto-config: the route locator that parses the
// YAML route table, the global CORS handler, the load-balancer client filter that gives lb:// meaning,
// and our CorrelationIdGlobalFilter. This proves all of that is internally consistent and the context
// starts.
//
// It runs OFFLINE — no Eureka, no downstream services needed. We turn the Eureka client OFF so the
// gateway doesn't try to register or fetch the registry while the context starts (the lb:// routes are
// only resolved on a real request, never at startup), and bind to an ephemeral port so the test never
// collides with a real gateway on 8080 during the reactor build.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.port=0",
                "eureka.client.enabled=false",
                "eureka.client.register-with-eureka=false",
                "eureka.client.fetch-registry=false"
        })
@DisplayName("api-gateway boots as a WebFlux gateway with its route table")
class ApiGatewayApplicationTests {

    @Test
    @DisplayName("the API gateway application context loads")
    void contextLoads() {
        // If the gateway/route/CORS/load-balancer auto-config or the global filter were broken, the
        // context would fail to start and this test would never reach its (empty) body.
    }
}
