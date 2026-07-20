package dev.dev48v.orderhub.saga;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Day 28 — a small READ-ONLY window onto the choreography saga, mirroring payment-service's PaymentController.
// The saga is driven entirely by EVENTS (StockReserved + PaymentProcessed), not by HTTP requests, so this
// controller writes nothing — it just exposes the saga log so each order's progress (which legs answered, and
// whether it shipped or was compensated) is observable directly: `curl localhost:8082/api/saga`.
@RestController
@RequestMapping("/api/saga")
public class SagaController {

    private final OrderSaga saga;

    public SagaController(OrderSaga saga) {
        this.saga = saga;
    }

    // Every saga the service has tracked so far.
    @GetMapping
    public List<SagaView> list() {
        return List.copyOf(saga.all());
    }

    // The saga for one order, or 404 if this service hasn't seen any result for that order yet.
    @GetMapping("/{orderId}")
    public ResponseEntity<SagaView> getOne(@PathVariable String orderId) {
        return saga.forOrder(orderId).stream().findFirst()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
