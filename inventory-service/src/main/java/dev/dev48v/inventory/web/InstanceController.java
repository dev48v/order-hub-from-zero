package dev.dev48v.inventory.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// Day 22 — expose THIS inventory-service instance's identity so client-side load balancing is observable.
//
// The whole point of running inventory-service as SEVERAL instances is to watch order-service's load
// balancer spread calls across them. But the calls all look identical from the outside, so we need each
// instance to say WHO answered. This endpoint returns the instance's service id, its Eureka instance id and
// its port — the values that differ between instances. order-service calls it through the load-balanced
// Feign client repeatedly and tallies the answers (GET /api/inventory-lb/distribution), and each hit is also
// logged here, so the distribution is visible from either side.
//
// Running multiple instances needs no code change: each is the same jar started with a different port, e.g.
//   SERVER_PORT=8091 java -jar inventory-service.jar
//   SERVER_PORT=8092 java -jar inventory-service.jar
// server.port is bound from that env var / -Dserver.port, and eureka.instance.instance-id already includes
// the port (${spring.application.name}:${server.port}), so all instances register under the one service name
// "inventory-service" but with distinct, identifiable ids — exactly what the load balancer rotates across.
@RestController
public class InstanceController {

    private static final Logger log = LoggerFactory.getLogger(InstanceController.class);

    private final String serviceId;
    private final int port;
    private final String instanceId;

    public InstanceController(
            @Value("${spring.application.name}") String serviceId,
            @Value("${server.port}") int port,
            @Value("${eureka.instance.instance-id:${spring.application.name}:${server.port}}") String instanceId) {
        this.serviceId = serviceId;
        this.port = port;
        this.instanceId = instanceId;
    }

    // Report which instance served this call. The matching path/shape is what order-service's Feign client
    // binds to (InventoryServiceClient.whichInstance -> InstanceView).
    @GetMapping("/api/inventory/instance")
    public InstanceView whichInstance() {
        log.info("inventory-service instance '{}' (port {}) served an /instance call", instanceId, port);
        return new InstanceView(serviceId, instanceId, port);
    }

    // The wire contract for an instance's identity.
    public record InstanceView(String serviceId, String instanceId, int port) {
    }
}
