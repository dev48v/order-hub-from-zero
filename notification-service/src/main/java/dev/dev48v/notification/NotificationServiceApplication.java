package dev.dev48v.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Day 41 — the entry point of the FIFTH Spring Boot application in the monorepo, and the first that is a pure,
// DEAD-END event CONSUMER: it reads the system's lifecycle facts and reacts by messaging the customer, but it
// publishes NO events of its own. Like payment-service and shipping-service it boots its OWN embedded Tomcat,
// its OWN Spring context, and component-scans its OWN package (dev.dev48v.notification downward) — disjoint
// from the other services' trees. Run `mvn -pl notification-service spring-boot:run` (or launch its jar) and
// you get a standalone Notification service on port 8085 that subscribes to order-placed, payment-events and
// shipping-events and, per event, sends the customer a templated notification (email + optional SMS).
//
// WHY a whole separate main(): a microservice is an independently-deployable process, not a package inside
// another app. This service owns exactly one thing — turning a lifecycle fact into a customer notification —
// and nothing more. And it is the cleanest possible demonstration of event-driven decoupling: a brand-new
// capability added to the whole system purely by LISTENING to facts that were already on the wire — no
// producer changed, no other service redeployed.
@SpringBootApplication
public class NotificationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
