package dev.dev48v.inventory;

import dev.dev48v.inventory.web.InventoryController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

// Day 17 — proof that the extraction produced a REAL, bootable second Spring Boot app. This
// @SpringBootTest starts the full inventory-service context (its own auto-config, its own beans)
// and asserts the controller wired up. If the split were only cosmetic — a package with no runnable
// app — this test could not load a context at all. It's the smoke test that the microservice stands
// on its own.
//
// Day 19 — the Eureka client is now on the classpath, so a full boot would otherwise try to register
// with a registry that isn't running during the build. eureka.client.enabled=false switches discovery
// off for the test so the context loads instantly with no background registration/heartbeat threads or
// connection-refused noise. Registering against a real registry is a runtime concern, not a unit-test one.
@SpringBootTest(properties = "eureka.client.enabled=false")
@DisplayName("inventory-service boots as its own Spring Boot application")
class InventoryServiceApplicationTests {

    @Autowired
    private InventoryController inventoryController;

    @Test
    @DisplayName("the application context loads and the inventory controller is present")
    void contextLoads() {
        assertThat(inventoryController).isNotNull();
    }
}
