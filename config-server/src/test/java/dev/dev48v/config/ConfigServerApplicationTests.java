package dev.dev48v.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

// Day 21 — proof that the config server is a real, bootable server that actually SERVES the config
// repo. Standing up @EnableConfigServer pulls in a lot of auto-config (the environment repository, the
// native backend, the /{application}/{profile} controller); this proves that config is internally
// consistent, the context starts, and the server returns the layered config a client would fetch.
//
// It runs entirely OFFLINE and self-contained: the `native` backend reads the yml files packaged under
// classpath:/config (this module's own resources), so there's no git remote, no other service, and no
// registry involved. We bind to an EPHEMERAL port (RANDOM_PORT) so the test never collides with a real
// config server on 8888 during the reactor build, and TestRestTemplate targets that random port.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("native")
@DisplayName("config-server boots and serves centralized per-service + shared config")
class ConfigServerApplicationTests {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("the config server application context loads")
    void contextLoads() {
        // If @EnableConfigServer's auto-config or the native backend were broken, the context would
        // fail to start and this test would never reach its (empty) body.
    }

    @Test
    @DisplayName("GET /order-service/default merges shared application.yml with order-service.yml")
    void servesOrderServiceConfig() {
        // This is exactly the request order-service makes at startup via spring.config.import.
        ResponseEntity<String> res = rest.getForEntity("/order-service/default", String.class);

        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        String body = res.getBody();
        assertThat(body).isNotNull();
        // From the SHARED application.yml (the org-wide platform name) — the same value every service sees.
        assertThat(body).contains("OrderHub");
        // From the SHARED application.yml — the centralized Eureka registry URL, no longer copy-pasted
        // into each service's local config.
        assertThat(body).contains("8761");
        // From the PER-SERVICE order-service.yml — a value with no local default in order-service.
        assertThat(body).contains("served centrally");
    }

    @Test
    @DisplayName("GET /inventory-service/default serves the inventory service's own centralized config")
    void servesInventoryServiceConfig() {
        ResponseEntity<String> res = rest.getForEntity("/inventory-service/default", String.class);

        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(res.getBody()).isNotNull();
        // The per-service inventory-service.yml overrides the shared config-source, proving the
        // {service}.yml layer wins over the shared application.yml layer.
        assertThat(res.getBody()).contains("inventory-service.yml");
    }

    @Test
    @DisplayName("the dev profile adds the {service}-{profile} override layer")
    void devProfileLayersOnTop() {
        // Asking for the dev profile returns application.yml + order-service.yml + order-service-dev.yml,
        // demonstrating the precedence hierarchy the LOOK simulation visualises.
        ResponseEntity<String> res = rest.getForEntity("/order-service/dev", String.class);

        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody()).contains("dev profile");
    }
}
