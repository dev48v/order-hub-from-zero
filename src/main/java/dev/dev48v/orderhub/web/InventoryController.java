package dev.dev48v.orderhub.web;

import dev.dev48v.orderhub.inventory.InventoryClient;
import dev.dev48v.orderhub.inventory.InventoryStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Day 14 — a small endpoint that EXERCISES the circuit breaker so it's observable end to end.
// WHY a dedicated endpoint: the breaker guards InventoryClient.checkStock(), and this is the HTTP
// door onto that call. Hitting it repeatedly with the downstream dialled to "flaky" (see the /failure-rate
// toggle) drives real failures into the breaker's sliding window until it trips OPEN — at which point
// this endpoint keeps returning 200 with a DEGRADED body from the fallback instead of 500-ing. That's
// the whole lesson: a failing dependency degrades gracefully rather than taking the API down with it.
//
// The toggle endpoints (/failure-rate) exist purely to make the breaker demonstrable without a real
// broken microservice — they set the stub's failure probability at runtime so you can watch CLOSED →
// OPEN → HALF_OPEN → CLOSED live via /actuator/health and /actuator/circuitbreakerevents.
@RestController
@RequestMapping("/api/inventory")
@Tag(name = "Inventory", description =
        "Check item stock through a circuit-breaker-guarded downstream (Day 14). "
                + "Returns a graceful degraded response when the breaker is OPEN.")
public class InventoryController {

    private final InventoryClient inventoryClient;

    public InventoryController(InventoryClient inventoryClient) {
        this.inventoryClient = inventoryClient;
    }

    // The guarded read. On a healthy downstream this returns a live "in stock" answer; when the
    // downstream is failing enough to trip the breaker, the SAME call returns a 200 with degraded=true
    // from the fallback — never a 500. `source` tells you which happened.
    @GetMapping("/{item}")
    @Operation(summary = "Check stock for an item (circuit-breaker guarded)",
            description = "Calls the downstream inventory service through the 'inventory' circuit breaker. "
                    + "If the breaker is OPEN (or the call fails), a graceful degraded response is returned "
                    + "with degraded=true and source=fallback — a 200, not a 500.")
    @ApiResponse(responseCode = "200",
            description = "Stock status — live from the service, or a degraded fallback answer")
    public InventoryStatus checkStock(
            @Parameter(description = "Item name to check", example = "Keyboard")
            @PathVariable String item) {
        return inventoryClient.checkStock(item);
    }

    // Demo/test control: set the stubbed downstream's failure probability (0.0 = healthy, 1.0 = fully
    // down). Flip it to ~0.8 and hammer GET /api/inventory/{item} a dozen times to trip the breaker
    // OPEN; drop it back to 0.0 and, after the wait duration, watch it recover through HALF_OPEN.
    // A POST because it mutates server-side state.
    @PostMapping("/failure-rate")
    @Operation(summary = "Set the downstream failure rate (demo control)",
            description = "Adjusts how often the stubbed inventory downstream fails, so the circuit "
                    + "breaker can be driven OPEN and back to CLOSED on demand. 0.0 = always healthy, "
                    + "1.0 = always failing.")
    @ApiResponse(responseCode = "200", description = "The failure rate now in effect")
    public FailureRateResponse setFailureRate(
            @Parameter(description = "Failure probability in [0.0, 1.0]", example = "0.8")
            @RequestParam double rate) {
        inventoryClient.setFailureRate(rate);
        return new FailureRateResponse(inventoryClient.getFailureRate());
    }

    // A tiny response record for the toggle so the endpoint returns JSON, not a bare number.
    public record FailureRateResponse(double failureRate) {
    }
}
