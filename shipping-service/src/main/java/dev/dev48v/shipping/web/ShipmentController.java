package dev.dev48v.shipping.web;

import dev.dev48v.shipping.shipment.ShipmentLedger;
import dev.dev48v.shipping.web.dto.ShipmentView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Day 28 — shipping-service's small HTTP surface. Like payment-service, shipping-service is driven by EVENTS,
// not requests: it ships or cancels an order because a PaymentProcessed arrived, never because someone called
// it. So this controller is READ-ONLY — a window onto the saga's outcomes (which orders were confirmed+shipped,
// which were compensated/cancelled), so the choreography is observable directly (curl localhost:8084/api/shipments)
// and demoable. The writes all happen in the @KafkaListener.
@RestController
@RequestMapping("/api/shipments")
public class ShipmentController {

    private final ShipmentLedger ledger;

    public ShipmentController(ShipmentLedger ledger) {
        this.ledger = ledger;
    }

    // Every saga outcome recorded so far — shipped orders and cancelled ones.
    @GetMapping
    public List<ShipmentView> list() {
        return ledger.all().stream().map(ShipmentView::from).toList();
    }

    // The outcome recorded for one order, or 404 if the saga hasn't settled that order yet.
    @GetMapping("/{orderId}")
    public ResponseEntity<ShipmentView> getOne(@PathVariable String orderId) {
        return ledger.forOrder(orderId)
                .map(ShipmentView::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
