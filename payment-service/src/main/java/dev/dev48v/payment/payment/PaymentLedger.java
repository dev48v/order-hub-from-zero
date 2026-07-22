package dev.dev48v.payment.payment;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// Day 27 — the IDEMPOTENCY guard + payment record for the consumer, the direct analogue of inventory-
// service's ReservationLedger. Kafka guarantees AT-LEAST-ONCE delivery: after a rebalance, a retry, or a
// crash between processing and offset-commit, the SAME OrderPlaced can arrive twice. For payments that is
// the worst possible bug — processing an event twice would CHARGE THE ORDER TWICE. The fix is to make
// handling IDEMPOTENT by keying on a stable business id (the order id) and processing each order exactly
// once. claim() is the atomic "first caller wins" primitive that makes a redelivery a no-op.
//
// Deliberately in-memory (a ConcurrentHashMap, thread-safe for the container's consumer threads): today's
// story is the event-driven service + its emitted result, not persistence — order-service already
// demonstrates the JPA/Postgres stack. In production this "charged orders" set would live in the service's
// own database so idempotency survives a restart; the shape of the check is identical.
@Component
public class PaymentLedger {

    // The set of order ids already charged — the idempotency key space. A map only because putIfAbsent
    // gives the atomic "claim it if I'm the first" primitive claim() needs.
    private final Map<String, Boolean> claimed = new ConcurrentHashMap<>();

    // The decided payment recorded for each processed order.
    private final Map<String, Payment> payments = new ConcurrentHashMap<>();

    // Atomically CLAIM an order id. Returns true ONLY for the first caller, false for every later (duplicate)
    // delivery of the same order — so the listener can skip a redelivery BEFORE any charge is made.
    // putIfAbsent is a single atomic operation, so two concurrent deliveries can never both win.
    public boolean claim(String orderId) {
        return claimed.putIfAbsent(orderId, Boolean.TRUE) == null;
    }

    // Day 31 — RELEASE a claim whose processing then FAILED with an unexpected/technical error, so a
    // legitimate retry can re-process the same record instead of hitting the duplicate-skip branch above (which
    // would make the failure look "handled" and keep it off the dead-letter topic). An order is "seen" only
    // once it has genuinely been charged or recorded — not merely because a first, failed attempt claimed it.
    public void unclaim(String orderId) {
        claimed.remove(orderId);
    }

    public void record(Payment payment) {
        payments.put(payment.getOrderId(), payment);
    }

    public Optional<Payment> forOrder(String orderId) {
        return Optional.ofNullable(payments.get(orderId));
    }

    public List<Payment> all() {
        return List.copyOf(payments.values());
    }

    public int size() {
        return payments.size();
    }
}
