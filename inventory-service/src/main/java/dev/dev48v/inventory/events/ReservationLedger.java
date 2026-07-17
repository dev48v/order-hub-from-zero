package dev.dev48v.inventory.events;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// Day 26 — the IDEMPOTENCY guard + reservation audit trail for the consumer. Kafka guarantees
// AT-LEAST-ONCE delivery: after a rebalance, a retry, or a crash between processing and offset-commit,
// the SAME event can arrive again. If the listener reserved stock every time it saw an event, one order
// could quietly reserve its quantity twice. The fix is to make handling IDEMPOTENT — safe to run more
// than once — by keying on a stable business id (the order id) and processing each order exactly once.
//
// This is deliberately a simple in-memory store (a ConcurrentHashMap, thread-safe for the container's
// consumer threads): today's story is the consumer + idempotency, not persistence — order-service already
// demonstrates the JPA/Postgres stack. In production this "seen orders" set would live in the service's
// own database (or a Redis set) so idempotency survives a restart; the shape of the check is identical.
@Component
public class ReservationLedger {

    // The set of order ids already handled — the idempotency key space. A map is used only because
    // putIfAbsent gives us the atomic "claim it if I'm the first" primitive claim() needs.
    private final Map<String, Boolean> claimed = new ConcurrentHashMap<>();

    // The outcome recorded for each processed order (RESERVED / INSUFFICIENT_STOCK / UNKNOWN_SKU).
    private final Map<String, Reservation> reservations = new ConcurrentHashMap<>();

    // Atomically CLAIM an order id. Returns true ONLY for the first caller to claim it, and false for
    // every later (duplicate) delivery of the same order — so the listener can skip the redelivery.
    // putIfAbsent is a single atomic operation, so two concurrent deliveries can never both win.
    public boolean claim(String orderId) {
        return claimed.putIfAbsent(orderId, Boolean.TRUE) == null;
    }

    public void record(Reservation reservation) {
        reservations.put(reservation.orderId(), reservation);
    }

    public Optional<Reservation> forOrder(String orderId) {
        return Optional.ofNullable(reservations.get(orderId));
    }

    public List<Reservation> all() {
        return List.copyOf(reservations.values());
    }

    public int size() {
        return reservations.size();
    }
}
