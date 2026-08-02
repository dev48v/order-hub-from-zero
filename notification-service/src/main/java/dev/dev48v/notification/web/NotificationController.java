package dev.dev48v.notification.web;

import dev.dev48v.notification.notify.NotificationLedger;
import dev.dev48v.notification.web.dto.NotificationView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Day 41 — a small READ surface so the notification feature is observable directly, the same way
// shipping-service exposes GET /api/shipments. The sends themselves happen in the @KafkaListener; this just
// lets you SEE what was dispatched: `curl localhost:8085/api/notifications` for everything, or
// `.../api/notifications/order/{orderId}` for one order's message trail (placed -> paid -> shipped). It reads
// from the in-memory NotificationLedger — the same ledger the dispatcher records into and the idempotency guard
// uses — so the API reflects exactly what actually went out, deduped.
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationLedger ledger;

    public NotificationController(NotificationLedger ledger) {
        this.ledger = ledger;
    }

    // Every notification dispatched so far, newest-first not guaranteed — insertion (send) order.
    @GetMapping
    public List<NotificationView> all() {
        return ledger.all().stream().map(NotificationView::from).toList();
    }

    // One order's notification trail — its "placed / paid / shipped" messages across channels.
    @GetMapping("/order/{orderId}")
    public List<NotificationView> forOrder(@PathVariable String orderId) {
        return ledger.forOrder(orderId).stream().map(NotificationView::from).toList();
    }
}
