package dev.dev48v.shipping.shipment;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// Day 28 — the IDEMPOTENCY guard + saga record for shipping-service, the direct analogue of inventory-service's
// ReservationLedger and payment-service's PaymentLedger. Kafka guarantees AT-LEAST-ONCE delivery: after a
// rebalance, a retry, or a crash between processing and offset-commit, the SAME PaymentProcessed can arrive
// twice. If the saga acted on every delivery, one order could be shipped twice (two tracking numbers, two
// notifications) or compensated twice (stock released twice). The fix is to make handling IDEMPOTENT by keying
// on a stable business id — the order id — and processing each order's outcome exactly once. claim() is the
// atomic "first caller wins" primitive that turns a redelivery into a no-op BEFORE any event is emitted.
//
// Deliberately in-memory (a ConcurrentHashMap, thread-safe for the container's consumer threads): today's
// story is the choreography saga + its compensating event, not persistence — order-service already demonstrates
// the JPA/Postgres stack. In production this "settled orders" set + the shipments would live in the service's
// own database so the saga's decisions survive a restart; the shape of the check is identical.
@Component
public class ShipmentLedger {

    // The set of order ids the saga has already settled — the idempotency key space. A map only because
    // putIfAbsent gives the atomic "claim it if I'm the first" primitive claim() needs.
    private final Map<String, Boolean> claimed = new ConcurrentHashMap<>();

    // The shipment/cancellation recorded for each settled order.
    private final Map<String, Shipment> shipments = new ConcurrentHashMap<>();

    // Atomically CLAIM an order id. Returns true ONLY for the first caller, false for every later (duplicate)
    // delivery of the same order — so the listener can skip a redelivery BEFORE it ships or compensates.
    // putIfAbsent is a single atomic operation, so two concurrent deliveries can never both win.
    public boolean claim(String orderId) {
        return claimed.putIfAbsent(orderId, Boolean.TRUE) == null;
    }

    public void record(Shipment shipment) {
        shipments.put(shipment.getOrderId(), shipment);
    }

    public Optional<Shipment> forOrder(String orderId) {
        return Optional.ofNullable(shipments.get(orderId));
    }

    public List<Shipment> all() {
        return List.copyOf(shipments.values());
    }

    public int size() {
        return shipments.size();
    }
}
