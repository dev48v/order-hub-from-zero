package dev.dev48v.orderhub;

import dev.dev48v.orderhub.config.IdempotencyProperties;
import dev.dev48v.orderhub.config.InventoryLoadBalancerConfig;
import dev.dev48v.orderhub.config.OrderProperties;
import dev.dev48v.orderhub.config.RateLimitProperties;
import dev.dev48v.orderhub.config.ServiceAuthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.loadbalancer.annotation.LoadBalancerClient;
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
//
// Day 19: no new annotation needed for service discovery. Simply having spring-cloud-starter-netflix-
// eureka-client on the classpath auto-configures a Eureka client that (a) REGISTERS this app with the
// registry on startup, and (b) gives Feign a DiscoveryClient so @FeignClient(name = "inventory-service")
// resolves by NAME + load-balances instead of using a hardcoded URL. (@EnableDiscoveryClient is optional
// since Spring Cloud auto-enables discovery when a client is present, so we keep the app class unchanged.)
//
// Day 22: @LoadBalancerClient binds a CUSTOM, per-service load-balancer configuration to the
// "inventory-service" client. Until today the caller used Spring Cloud's DEFAULT round-robin balancer
// implicitly; now InventoryLoadBalancerConfig supplies our own instance-list supplier (discovery +
// health-check filtering + caching) and a switchable strategy (round-robin default, or random). The config
// applies ONLY to inventory-service — any other load-balanced client would keep the defaults. Note the
// referenced config class is deliberately NOT component-scanned (it carries no @Configuration); Spring Cloud
// instantiates it in the isolated child context for just this client, which is why binding it here — by name
// — is the correct wiring point.
//
// Day 24: ServiceAuthProperties joins the @EnableConfigurationProperties list, binding "service.auth.*"
// (the header + the ${SERVICE_TOKEN}-sourced token). ServiceTokenFeignInterceptor injects it and stamps
// the token on every outbound Feign call, so order-service authenticates to inventory-service's new
// service-token gate. No new class-level annotation is needed — the interceptor is a @Component picked up
// by the component scan and applied globally by Feign.
@SpringBootApplication
@EnableFeignClients
@LoadBalancerClient(name = "inventory-service", configuration = InventoryLoadBalancerConfig.class)
@EnableConfigurationProperties({OrderProperties.class, RateLimitProperties.class, IdempotencyProperties.class, ServiceAuthProperties.class})
public class OrderHubApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderHubApplication.class, args);
    }
}
