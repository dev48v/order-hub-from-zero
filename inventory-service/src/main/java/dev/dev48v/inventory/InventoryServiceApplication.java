package dev.dev48v.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Day 17 — the entry point of the SECOND Spring Boot application.
// WHY a whole separate main(): a microservice is an independently-deployable process, not a
// package inside another app. This class boots its OWN embedded Tomcat, its OWN Spring context,
// and component-scans its OWN package (dev.dev48v.inventory downward) — completely disjoint from
// order-service's dev.dev48v.orderhub tree. Run `mvn -pl inventory-service spring-boot:run` (or
// launch its jar) and you get a standalone Inventory API on port 8081, alongside the Order API on
// 8080. Two processes, two lifecycles, one monorepo.
//
// Note what is NOT here: no @EnableConfigurationProperties, no cache/rate-limit/resilience wiring.
// This service is deliberately small — it owns stock levels and reservations, nothing more. That
// focus is the whole point of carving a bounded context out of the monolith.
@SpringBootApplication
public class InventoryServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
