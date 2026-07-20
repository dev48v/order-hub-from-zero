package dev.dev48v.shipping;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Day 28 — the entry point of the FOURTH Spring Boot application in the monorepo, and the service that
// completes the CHOREOGRAPHY SAGA (order -> inventory -> payment -> ship). Like payment-service it is
// wholly event-driven: it boots its OWN embedded Tomcat, its OWN Spring context, and component-scans its
// OWN package (dev.dev48v.shipping downward) — disjoint from order-service's dev.dev48v.orderhub,
// inventory-service's dev.dev48v.inventory and payment-service's dev.dev48v.payment trees. Run
// `mvn -pl shipping-service spring-boot:run` (or launch its jar) and you get a standalone Shipping service
// on port 8084 that subscribes to payment-events and, per the payment decision, either SHIPS the order
// (emits ShipmentScheduled) or COMPENSATES it (emits OrderCancelled so inventory-service releases stock).
//
// WHY a whole separate main(): a microservice is an independently-deployable process, not a package inside
// another app. This service owns exactly one thing — turning a payment result into the order's final fate,
// shipped or cancelled — and nothing more. That focus is the whole point of a bounded context.
@SpringBootApplication
public class ShippingServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShippingServiceApplication.class, args);
    }
}
