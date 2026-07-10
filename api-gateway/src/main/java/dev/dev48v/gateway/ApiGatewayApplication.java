package dev.dev48v.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

// Day 20 — the entry point for the API GATEWAY: the single front door to the whole system.
//
// There is deliberately almost no code here. The gateway's behaviour — which paths route to which
// service, and the filters applied along the way — is DECLARATIVE, defined in application.yml, not
// wired in Java. That's the Spring Cloud Gateway model: a route table (predicate -> uri + filters)
// evaluated per request. This class just boots the WebFlux/Netty app that runs that table.
//
// @EnableDiscoveryClient registers this gateway with Eureka and, more importantly, gives it a
// DiscoveryClient so the lb:// URIs in the route table (lb://order-service, lb://inventory-service)
// resolve to LIVE instances at request time instead of hardcoded hosts. It's optional with the
// eureka-client starter on the classpath (auto-enabled), but stated here to make the intent explicit.
//
// The one bit of real Java behaviour is CorrelationIdGlobalFilter — a global (every-route) filter,
// picked up as a @Component — showing how a cross-cutting concern is implemented ONCE at the edge.
@SpringBootApplication
@EnableDiscoveryClient
public class ApiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
