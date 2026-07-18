package dev.dev48v.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Day 27 — the entry point of the THIRD Spring Boot application in the monorepo, and the first that is
// wholly event-driven: it is triggered by an event and answers with an event, never by an inbound HTTP
// request. WHY a whole separate main(): a microservice is an independently-deployable process, not a
// package inside another app. This class boots its OWN embedded Tomcat, its OWN Spring context, and
// component-scans its OWN package (dev.dev48v.payment downward) — completely disjoint from
// order-service's dev.dev48v.orderhub and inventory-service's dev.dev48v.inventory trees. Run
// `mvn -pl payment-service spring-boot:run` (or launch its jar) and you get a standalone Payment service
// on port 8083 that subscribes to order-placed and emits PaymentProcessed to payment-events.
//
// Like inventory-service, this service is deliberately small — it owns one decision (approve or decline an
// order's payment) and nothing more. That focus is the whole point of a bounded context.
@SpringBootApplication
public class PaymentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}
