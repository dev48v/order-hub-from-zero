package dev.dev48v.orderhub.web;

import dev.dev48v.orderhub.inventory.InstanceView;
import dev.dev48v.orderhub.inventory.InventoryServiceClient;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

// Day 22 — the endpoint that makes CLIENT-SIDE LOAD BALANCING observable end to end.
//
// It calls inventory-service's identity endpoint through the load-balanced Feign client, once per request
// for /instance and N times for /distribution, and reports which instance(s) answered. Run inventory-service
// as two or three instances (each on its own port, e.g. SERVER_PORT=8091 / 8092), then:
//   GET /api/inventory-lb/instance             -> one call; shows the single instance the balancer chose
//   GET /api/inventory-lb/distribution?calls=12 -> 12 calls; tallies hits per instance so the strategy shows
// With the default round-robin strategy the tally is even across instances (12 calls / 3 instances = 4 each);
// flip orderhub.loadbalancer.strategy=random and the split becomes uneven/random. Kill one instance and its
// health check fails, so it drops out of the tally and traffic rebalances onto the survivors — no restart of
// order-service, no config change, all decided in the caller.
@RestController
@RequestMapping("/api/inventory-lb")
@Tag(name = "Load balancing", description =
        "Observe Spring Cloud LoadBalancer (Day 22): call inventory-service through the load-balanced "
                + "client and see which instance answers as the balancer spreads requests across instances.")
public class LoadBalancerController {

    private final InventoryServiceClient inventoryServiceClient;

    // Surfaced so the response can report which strategy is in effect — the same property
    // InventoryLoadBalancerConfig reads to pick the round-robin vs random balancer.
    private final String strategy;

    public LoadBalancerController(InventoryServiceClient inventoryServiceClient,
                                  @Value("${orderhub.loadbalancer.strategy:round-robin}") String strategy) {
        this.inventoryServiceClient = inventoryServiceClient;
        this.strategy = strategy;
    }

    // One load-balanced call — returns the identity of whichever instance the balancer picked this time.
    @GetMapping("/instance")
    @Operation(summary = "Which inventory-service instance answered (one load-balanced call)")
    @ApiResponse(responseCode = "200", description = "The instance the client-side load balancer chose")
    public InstanceView instance() {
        return inventoryServiceClient.whichInstance();
    }

    // Fire N load-balanced calls and tally the answering instances — the distribution IS the strategy made
    // visible. Round-robin -> even split; random -> random split; a dead instance -> absent from the tally.
    @GetMapping("/distribution")
    @Operation(summary = "Distribution across instances over N load-balanced calls",
            description = "Calls inventory-service N times through the load balancer and counts how many "
                    + "calls each instance served, so round-robin vs random (and instance health) is visible.")
    @ApiResponse(responseCode = "200", description = "Per-instance hit counts plus the strategy in effect")
    public Map<String, Object> distribution(
            @Parameter(description = "How many load-balanced calls to make", example = "12")
            @RequestParam(defaultValue = "12") int calls) {
        Map<String, Integer> hits = new TreeMap<>();
        for (int i = 0; i < calls; i++) {
            InstanceView who = inventoryServiceClient.whichInstance();
            hits.merge(who.instanceId(), 1, Integer::sum);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("strategy", strategy);
        result.put("calls", calls);
        result.put("instances", hits.size());
        result.put("distribution", hits);
        return result;
    }
}
