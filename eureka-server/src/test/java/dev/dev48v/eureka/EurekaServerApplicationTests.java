package dev.dev48v.eureka;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Day 19 — smoke test that the registry is a real, bootable app.
// @EnableEurekaServer pulls in a lot of auto-config (the registry, the peer-replication machinery,
// the dashboard); this proves that config is internally consistent and the context starts. We bind
// the web server to an EPHEMERAL port (server.port=0) so the test never collides with a real registry
// on 8761 during the reactor build, and keep register/fetch off so the standalone node doesn't try to
// reach a peer while starting.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "server.port=0",
                "eureka.client.register-with-eureka=false",
                "eureka.client.fetch-registry=false"
        })
@DisplayName("eureka-server boots as a standalone service registry")
class EurekaServerApplicationTests {

    @Test
    @DisplayName("the Eureka server application context loads")
    void contextLoads() {
        // If @EnableEurekaServer's auto-config were broken, the context would fail to start
        // and this test would never reach its (empty) body.
    }
}
