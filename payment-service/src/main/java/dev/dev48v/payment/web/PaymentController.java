package dev.dev48v.payment.web;

import dev.dev48v.payment.payment.PaymentLedger;
import dev.dev48v.payment.web.dto.PaymentView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Day 27 — payment-service's small HTTP surface. Unlike inventory-service, payment-service is driven by
// EVENTS, not requests: it makes no payment because someone called it, it makes one because an OrderPlaced
// arrived. So this controller is READ-ONLY — a window onto the decisions the service has already recorded,
// so its behaviour is observable directly (curl localhost:8083/api/payments) and demoable. The writes all
// happen in the @KafkaListener.
@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentLedger ledger;

    public PaymentController(PaymentLedger ledger) {
        this.ledger = ledger;
    }

    // Every payment decision recorded so far.
    @GetMapping
    public List<PaymentView> list() {
        return ledger.all().stream().map(PaymentView::from).toList();
    }

    // The payment recorded for one order, or 404 if this service hasn't processed that order.
    @GetMapping("/{orderId}")
    public ResponseEntity<PaymentView> getOne(@PathVariable String orderId) {
        return ledger.forOrder(orderId)
                .map(PaymentView::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
