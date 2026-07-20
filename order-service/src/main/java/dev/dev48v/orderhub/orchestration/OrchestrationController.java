package dev.dev48v.orderhub.orchestration;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Day 29 — a small READ-ONLY window onto the ORCHESTRATION saga, mirroring Day 28's SagaController. The
// orchestrator is driven entirely by commands + replies over Kafka, not by HTTP, so this controller writes
// nothing — it just exposes the coordinator's per-order state machine so each order's progress (which step it
// reached, and whether it completed or was compensated) is observable directly: `curl localhost:8082/api/orchestration`.
@RestController
@RequestMapping("/api/orchestration")
public class OrchestrationController {

    private final SagaOrchestrator orchestrator;

    public OrchestrationController(SagaOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @GetMapping
    public List<OrchestrationView> list() {
        return List.copyOf(orchestrator.all());
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrchestrationView> getOne(@PathVariable String orderId) {
        return orchestrator.forOrder(orderId).stream().findFirst()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
