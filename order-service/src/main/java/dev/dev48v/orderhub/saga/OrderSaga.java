package dev.dev48v.orderhub.saga;

import dev.dev48v.orderhub.domain.Order;
import dev.dev48v.orderhub.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Day 28 — the CHOREOGRAPHY SAGA coordinator, order-service's half. There is NO central orchestrator giving
// orders: each service reacts to FACTS. order-service placed the order and emitted OrderPlaced; inventory and
// payment reacted independently and answered with their own facts (StockReserved, PaymentProcessed). This
// class is where order-service, in turn, REACTS to those two facts and drives the order to its terminal
// state — shipping it, or compensating. It issues no commands; it only listens and, based on what it has
// heard, either ships (forward) or cancels + emits a compensating event (backward). That "advance on success,
// undo on failure, all via events" shape is the whole point of a saga: there is no distributed transaction to
// roll back, so we roll FORWARD with events and roll BACK with compensating events.
//
// Two production-shaping properties, the same discipline as the Day-26/27 consumers:
//   • KEYED + IDEMPOTENT — everything is keyed on the order id. Each order's SagaState is created once
//     (computeIfAbsent) and every read-modify on it runs under `synchronized (state)`, so the two result
//     events — which arrive on different topics, in any order, possibly on different consumer threads — are
//     correlated safely. The state latches a `terminal` flag the instant it decides, so a redelivered event
//     (Kafka is at-least-once) re-enters evaluate() but the decision, the order mutation, and the emitted
//     event all happen EXACTLY ONCE.
//   • FAILURE FIRES EARLY, SUCCESS WAITS FOR BOTH — a decline or a stock failure compensates immediately
//     (no reason to wait for the other leg once the saga is doomed); shipping requires BOTH legs to have
//     succeeded. Ordering of the two events doesn't matter: whichever arrives second triggers the decision.
@Component
public class OrderSaga {

    private static final Logger log = LoggerFactory.getLogger(OrderSaga.class);

    static final String REASON_PAYMENT_DECLINED = "PAYMENT_DECLINED";
    static final String REASON_STOCK_UNAVAILABLE = "STOCK_UNAVAILABLE";

    private final OrderRepository orders;
    private final SagaResultPublisher publisher;

    // The saga log: one SagaState per order id. In-memory for the demo (the same simplification the Day-26
    // ReservationLedger and Day-27 PaymentLedger make); in production this would be the service's own database
    // so a saga survives a restart, but the correlation logic is identical.
    private final Map<String, SagaState> sagas = new ConcurrentHashMap<>();

    public OrderSaga(OrderRepository orders, SagaResultPublisher publisher) {
        this.orders = orders;
        this.publisher = publisher;
    }

    // Leg 1: inventory answered. Record the stock outcome, then re-evaluate the saga.
    public void onStockReserved(StockReservedEvent event) {
        SagaState state = sagas.computeIfAbsent(event.orderId(), SagaState::new);
        synchronized (state) {
            state.recordStock(event.outcome());
            log.info("Saga {} - stock result {}", event.orderId(), event.outcome());
            evaluate(state);
        }
    }

    // Leg 2: payment answered. Record the payment outcome + amount, then re-evaluate the saga.
    public void onPaymentProcessed(PaymentProcessedEvent event) {
        SagaState state = sagas.computeIfAbsent(event.orderId(), SagaState::new);
        synchronized (state) {
            state.recordPayment(event.status(), event.amount());
            log.info("Saga {} - payment result {}", event.orderId(), event.status());
            evaluate(state);
        }
    }

    // The decision function. MUST be called with the monitor on `state` held (both callers do). Idempotent:
    // once terminal, it returns immediately, so a redelivered event never re-ships or re-cancels an order.
    private void evaluate(SagaState state) {
        if (state.isTerminal()) {
            return; // already decided — the saga acts exactly once
        }

        // COMPENSATE EARLY on any failure — don't wait for the other leg once the saga can't succeed.
        if (state.paymentDeclined()) {
            compensate(state, REASON_PAYMENT_DECLINED);
            return;
        }
        if (state.stockFailed()) {
            compensate(state, REASON_STOCK_UNAVAILABLE);
            return;
        }

        // SHIP only when BOTH legs have succeeded.
        if (state.stockReserved() && state.paymentApproved()) {
            ship(state, state.amount());
            return;
        }

        // Otherwise one leg is still outstanding — keep waiting for the other event.
    }

    // Happy path: confirm + ship the order, latch the state, and announce OrderShipped.
    private void ship(SagaState state, BigDecimal amount) {
        Order order = orders.findById(state.orderId()).orElse(null);
        if (order == null) {
            // The order this saga is about isn't in our store (shouldn't happen in the real flow, since the
            // order was placed here). Latch terminal so a redelivery doesn't loop, and log it.
            log.warn("Saga {} approved+reserved but order not found - cannot ship", state.orderId());
            state.markShipped();
            return;
        }
        order.confirm();   // PLACED -> CONFIRMED
        order.ship();      // CONFIRMED -> SHIPPED
        orders.save(order);
        state.markShipped();
        log.info("Saga {} COMPLETE - order shipped (stock reserved + payment approved)", state.orderId());
        publisher.publishShipped(OrderShippedEvent.from(order, amount, "saga"));
    }

    // Compensation: cancel the order, latch the state, and emit the compensating OrderCancelled that
    // inventory-service reacts to by RELEASING the reserved stock. This is the saga UNDOING earlier steps
    // with an event, because there is no shared transaction to roll back.
    private void compensate(SagaState state, String reason) {
        Order order = orders.findById(state.orderId()).orElse(null);
        if (order == null) {
            log.warn("Saga {} failed ({}) but order not found - cannot cancel", state.orderId(), reason);
            state.markCancelled(reason);
            return;
        }
        order.cancel();    // -> CANCELLED (idempotent on the domain side too)
        orders.save(order);
        state.markCancelled(reason);
        log.info("Saga {} COMPENSATED - order cancelled ({}), releasing any reserved stock", state.orderId(), reason);
        publisher.publishCancelled(OrderCancelledEvent.from(order, reason));
    }

    // Read surface for the SagaController — a snapshot of every saga the service has tracked.
    public Collection<SagaView> all() {
        return sagas.values().stream().map(SagaView::from).toList();
    }

    public List<SagaView> forOrder(String orderId) {
        SagaState state = sagas.get(orderId);
        return state == null ? List.of() : List.of(SagaView.from(state));
    }
}
