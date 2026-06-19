package dev.dev48v.orderhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// STEP 1 — The entry point.
// WHY: @SpringBootApplication bundles three annotations — it turns on
// auto-configuration, component scanning of this package downward, and marks
// this as the config class. main() boots the embedded Tomcat and the whole
// dependency-injection container. One class, and you have a running server.
@SpringBootApplication
public class OrderHubApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderHubApplication.class, args);
    }
}
