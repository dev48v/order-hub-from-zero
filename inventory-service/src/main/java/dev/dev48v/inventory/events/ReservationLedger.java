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

    // Day 31 — RELEASE a claim that was taken but whose processing then FAILED with an unexpected/technical
    // error. Without this, the claim above would make every retry of that same record hit the duplicate-skip
    // branch and return "successfully" — so the record would never actually be reprocessed and never reach the
    // dead-letter topic. Un-claiming on failure keeps the idempotency guard honest: an order is "seen" only
    // once it has genuinely been handled (success or a recorded business outcome), so a legitimate retry can
    // run again and, if it keeps failing, be routed to the DLT.
    public void unclaim(String orderId) {
        claimed.remove(orderId);
    }

    public void record(Reservation reservation) {
        reservations.put(reservation.orderId(), reservation);
    }

    // Day 28 — the COMPENSATION primitive, made idempotent. Atomically flip an order's reservation from
    // RESERVED to RELEASED and return the reservation as it WAS (so the caller knows the sku + quantity to put
    // back). Returns empty when there is nothing to release — the order was never reserved (a failed/unknown
    // reservation), or it was ALREADY released by an earlier delivery of the same cancel event. Because Kafka
    // is at-least-once, the compensating OrderCancelled can arrive twice; computing the flip atomically and
    // only acting on a still-RESERVED entry means the stock is put back EXACTLY ONCE, never double-replenished.
    public Optional<Reservation> releaseIfReserved(String orderId) {
        Reservation[] released = new Reservation[1];
        reservations.compute(orderId, (id, current) -> {
            if (current != null && current.isReserved()) {
                released[0] = current;          // capture the pre-release snapshot for the caller
                return current.asReleased();    // latch RELEASED so a redelivery is a no-op
            }
            return current;                     // nothing to release — leave it untouched
        });
        return Optional.ofNullable(released[0]);
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
