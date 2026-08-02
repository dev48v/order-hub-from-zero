package dev.dev48v.notification.notify;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

// Day 41 — the IDEMPOTENCY guard + dispatched-notification record for notification-service, the direct analogue
// of shipping-service's ShipmentLedger and payment-service's PaymentLedger. Kafka guarantees AT-LEAST-ONCE
// delivery: after a rebalance, a retry, or a crash between processing and offset-commit, the SAME event can
// arrive twice. If we notified on every delivery, a customer would get the SAME "your order shipped" email
// twice. The fix is to make handling IDEMPOTENT by keying on a stable id — here the EVENT id (unique per
// emission) — and dispatching each event's notification exactly once.
//
// The event id (not the order id) is the right key: one order legitimately produces SEVERAL notifications over
// its life (placed, paid, shipped), each from a DIFFERENT event with its own id — so those must all go out —
// while a REDELIVERY of one of those events carries the SAME event id and must be suppressed. claim() is the
// atomic "first caller wins" primitive that turns a redelivery into a no-op BEFORE anything is sent.
//
// Deliberately in-memory (thread-safe collections for the container's consumer threads): today's story is the
// event-driven notification flow + its idempotency, not persistence — order-service already demonstrates the
// JPA/Postgres stack. In production this "already-notified" set + the sent-notification log would live in the
// service's own datastore so the guarantee survives a restart; the shape of the check is identical.
@Component
public class NotificationLedger {

    // The set of event ids already handled — the idempotency key space. A map only because putIfAbsent gives
    // the atomic "claim it if I'm the first" primitive claim() needs.
    private final Map<String, Boolean> handled = new ConcurrentHashMap<>();

    // Every notification actually dispatched, in send order — the read API's source and the test's assertion
    // surface. CopyOnWriteArrayList so the consumer threads can append while the web layer iterates safely.
    private final List<Notification> sent = new CopyOnWriteArrayList<>();

    // Atomically CLAIM an event id. Returns true ONLY for the first caller, false for every later (duplicate)
    // delivery of the same event — so the dispatcher can skip a redelivery BEFORE it sends anything.
    // putIfAbsent is a single atomic operation, so two concurrent deliveries can never both win.
    public boolean claim(String eventId) {
        return handled.putIfAbsent(eventId, Boolean.TRUE) == null;
    }

    // RELEASE a claim whose dispatch then FAILED with an unexpected/technical error, so a legitimate retry can
    // re-process the same record instead of hitting the duplicate-skip branch (which would make the failure look
    // "handled" and keep it off the dead-letter topic). An event is "handled" only once its notification has
    // genuinely gone out — not merely because a first, failed attempt claimed it.
    public void unclaim(String eventId) {
        handled.remove(eventId);
    }

    // Record a dispatched notification (called by the dispatcher AFTER the sender accepted it).
    public void record(Notification notification) {
        sent.add(notification);
    }

    public List<Notification> all() {
        return List.copyOf(sent);
    }

    public List<Notification> forOrder(String orderId) {
        return sent.stream().filter(n -> n.orderId().equals(orderId)).toList();
    }

    public int size() {
        return sent.size();
    }
}
