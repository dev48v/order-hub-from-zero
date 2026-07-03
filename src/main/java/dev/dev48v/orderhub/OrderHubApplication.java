package dev.dev48v.orderhub;

import dev.dev48v.orderhub.config.OrderProperties;
import dev.dev48v.orderhub.config.RateLimitProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

// STEP 1 — The entry point.
// WHY: @SpringBootApplication bundles three annotations — it turns on
// auto-configuration, component scanning of this package downward, and marks
// this as the config class. main() boots the embedded Tomcat and the whole
// dependency-injection container. One class, and you have a running server.
//
// Day 7: @EnableConfigurationProperties registers OrderProperties as a bean so it
// can be injected anywhere, and binds the "app.orders.*" keys onto it at startup.
// Day 13: RateLimitProperties joins it, binding "app.ratelimit.*" (capacity + refill period).
@SpringBootApplication
@EnableConfigurationProperties({OrderProperties.class, RateLimitProperties.class})
public class OrderHubApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderHubApplication.class, args);
    }
}
