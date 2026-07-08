package dev.dev48v.orderhub;

import dev.dev48v.orderhub.config.IdempotencyProperties;
import dev.dev48v.orderhub.config.OrderProperties;
import dev.dev48v.orderhub.config.RateLimitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;

// STEP 1 — The entry point.
// WHY: @SpringBootApplication bundles three annotations — it turns on
// auto-configuration, component scanning of this package downward, and marks
// this as the config class. main() boots the embedded Tomcat and the whole
// dependency-injection container. One class, and you have a running server.
//
// Day 7: @EnableConfigurationProperties registers OrderProperties as a bean so it
// can be injected anywhere, and binds the "app.orders.*" keys onto it at startup.
// Day 13: RateLimitProperties joins it, binding "app.ratelimit.*" (capacity + refill period).
// Day 16: IdempotencyProperties joins it too, binding "app.idempotency.*" (header, ttl, lock ttl).
//
// Day 18: @EnableFeignClients switches on OpenFeign. It scans this package downward for @FeignClient
// interfaces (here: InventoryServiceClient) and, for each, registers a proxy bean that implements the
// interface by making the HTTP calls its method annotations describe. That bean is then injectable like
// any other — OrderService receives it and calls inventory-service over the wire through it.
@SpringBootApplication
@EnableFeignClients
@EnableConfigurationProperties({OrderProperties.class, RateLimitProperties.class, IdempotencyProperties.class})
public class OrderHubApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderHubApplication.class, args);
    }
}
