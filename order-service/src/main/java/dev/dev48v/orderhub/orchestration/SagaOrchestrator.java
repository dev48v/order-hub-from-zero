package dev.dev48v.orderhub.orchestration;

import dev.dev48v.orderhub.domain.Order;
import dev.dev48v.orderhub.events.OrderPlacedEvent;
import dev.dev48v.orderhub.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// Day 29 — the CENTRAL SAGA COORDINATOR, and the whole point of the day: the CONTRAST to Day 28's choreography.
// In choreography there is no coordinator — services publish facts and react to each other, and the order flow
// is emergent, scattered across every service's listeners. HERE, ONE component owns the entire flow as an
// explicit state machine: it starts on OrderPlaced and drives the order forward by SENDING COMMANDS and
// AWAITING REPLIES —
//
//     ReserveStock  ─▶ (STOCK_RESERVED)  ─▶ ProcessPayment ─▶ (PAYMENT_APPROVED) ─▶ ScheduleShipment ─▶ (SHIPMENT_SCHEDULED) ─▶ COMPLETED
//                       └▶ (STOCK_REJECTED) ─▶ CANCELLED          └▶ (PAYMENT_DECLINED) ─▶ COMPENSATE: ReleaseStock (reverse) + CANCELLED
//
// The trade-off flips too: the flow is centralized and readable in ONE place (this class), at the cost of the
// coordinator being a component every participant now depends on — the mirror image of choreography's "no
// coupling, but the logic is everywhere".
//
// It keeps the SAME two production disciplines as the Day-28 saga:
//   • KEYED + IDEMPOTENT — one OrchestrationState per order id (computeIfAbsent), every read-modify guarded by
//     `synchronized (state)`. The current STEP is the idempotency guard: a redelivered reply for a step already
//     advanced past no longer matches the state, so it is ignored — each reply advances the flow exactly once,
//     and once terminal every further reply is dropped.
//   • NON-BLOCKING — commands are published through SagaCommandPublisher, which swallows all Kafka errors so
//     sending the next command from inside the reply-consumer thread can never crash or wedge it.
//
// This runs ALONGSIDE the choreography saga but is gated OFF by default (orderhub.orchestration.enabled=false),
// so the two patterns coexist in the codebase and you pick one per environment — flipping orchestration on (and
// choreography off) swaps the coordination style without touching the participant services' domain logic.
@Component
public class SagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SagaOrchestrator.class);

    static final String REASON_PAYMENT_DECLINED = "PAYMENT_DECLINED";
    static final String REASON_STOCK_UNAVAILABLE = "STOCK_UNAVAILABLE";

    private final OrderRepository orders;
    private final SagaCommandPublisher commands;

    // The saga log: one OrchestrationState per order id. In-memory for the demo (same simplification as the
    // Day-28 saga + the ledgers); in production this would be the coordinator's own database so an in-flight
    // saga survives a restart — the state-machine logic is identical.
    private final Map<String, OrchestrationState> sagas = new ConcurrentHashMap<>();

    public SagaOrchestrator(OrderRepository orders, SagaCommandPublisher commands) {
        this.orders = orders;
        this.commands = commands;
    }

    // ── START ──────────────────────────────────────────────────────────────────────────────────────────
    // The saga begins when the orchestrator hears OrderPlaced. It records the order's data and issues the FIRST
    // command (ReserveStock), moving to AWAITING_STOCK. Idempotent: a redelivered OrderPlaced finds a state that
    // has already left STARTED and does nothing, so the flow is never kicked off twice.
    public void onOrderPlaced(OrderPlacedEvent event) {
        OrchestrationState state = sagas.computeIfAbsent(event.orderId(),
                id -> new OrchestrationState(id, event.customer(), event.item(), event.quantity()));
        synchronized (state) {
            if (state.step() != OrchestrationStep.STARTED) {
                log.info("Orchestration {} already started ({}) - ignoring duplicate OrderPlaced",
                        state.orderId(), state.step());
                return;
            }
            state.awaitStock();
            log.info("Orchestration {} START -> sending RESERVE_STOCK", state.orderId());
            commands.send(command(state, SagaCommand.RESERVE_STOCK, null));
        }
    }

    // ── DRIVE ──────────────────────────────────────────────────────────────────────────────────────────
    // Every reply re-enters here. The step guard makes it idempotent and correct under any-order redelivery:
    // a reply only fires if the saga is in the state that AWAITS it, and never once terminal.
    public void onReply(SagaReply reply) {
        OrchestrationState state = sagas.get(reply.orderId());
        if (state == null) {
            log.warn("Reply {} for unknown saga {} - ignoring", reply.type(), reply.orderId());
            return;
        }
        synchronized (state) {
            if (state.isTerminal()) {
                return; // already decided — each saga acts exactly once
            }
            log.info("Orchestration {} reply {} (step {})", reply.orderId(), reply.type(), state.step());
            switch (reply.type()) {
                case SagaReply.STOCK_RESERVED     -> onStockReserved(state);
                case SagaReply.STOCK_REJECTED     -> onStockRejected(state);
                case SagaReply.PAYMENT_APPROVED   -> onPaymentApproved(state, reply.amount());
                case SagaReply.PAYMENT_DECLINED   -> onPaymentDeclined(state);
                case SagaReply.SHIPMENT_SCHEDULED -> onShipmentScheduled(state, reply.detail());
                case SagaReply.STOCK_RELEASED     -> log.info("Orchestration {} compensation ack - stock released",
                        state.orderId());
                default -> log.warn("Orchestration {} unknown reply type '{}'", state.orderId(), reply.type());
            }
        }
    }

    // stock reserved → advance to payment
    private void onStockReserved(OrchestrationState state) {
        if (state.step() != OrchestrationStep.AWAITING_STOCK) return; // redelivery / out-of-step -> ignore
        state.markStockReserved();
        state.awaitPayment();
        log.info("Orchestration {} stock reserved -> sending PROCESS_PAYMENT", state.orderId());
        commands.send(command(state, SagaCommand.PROCESS_PAYMENT, null));
    }

    // stock could not be reserved → cancel immediately (nothing to release; nothing was reserved)
    private void onStockRejected(OrchestrationState state) {
        if (state.step() != OrchestrationStep.AWAITING_STOCK) return;
        log.info("Orchestration {} stock REJECTED -> compensate (cancel, nothing reserved)", state.orderId());
        cancelOrder(state, REASON_STOCK_UNAVAILABLE);
    }

    // payment approved → confirm the order, advance to shipment
    private void onPaymentApproved(OrchestrationState state, BigDecimal amount) {
        if (state.step() != OrchestrationStep.AWAITING_PAYMENT) return;
        state.recordAmount(amount);
        confirmOrder(state);            // PLACED -> CONFIRMED
        state.awaitShipment();
        log.info("Orchestration {} payment APPROVED ({}) -> sending SCHEDULE_SHIPMENT", state.orderId(), amount);
        commands.send(command(state, SagaCommand.SCHEDULE_SHIPMENT, amount));
    }

    // payment declined → COMPENSATE IN REVERSE: issue ReleaseStock (undo the reserve), then cancel the order.
    private void onPaymentDeclined(OrchestrationState state) {
        if (state.step() != OrchestrationStep.AWAITING_PAYMENT) return;
        log.info("Orchestration {} payment DECLINED -> COMPENSATE: RELEASE_STOCK + cancel", state.orderId());
        if (state.stockReserved()) {
            // the compensating command, in reverse order of the forward steps — inventory un-reserves the units
            commands.send(command(state, SagaCommand.RELEASE_STOCK, null));
        }
        cancelOrder(state, REASON_PAYMENT_DECLINED);
    }

    // shipment scheduled → ship the order; the saga is COMPLETE
    private void onShipmentScheduled(OrchestrationState state, String tracking) {
        if (state.step() != OrchestrationStep.AWAITING_SHIPMENT) return;
        shipOrder(state);               // CONFIRMED -> SHIPPED
        state.complete();
        log.info("Orchestration {} COMPLETE - order shipped (tracking {})", state.orderId(), tracking);
    }

    // ── order mutations (mirror the choreography saga; latch state after mutating) ───────────────────────
    private void confirmOrder(OrchestrationState state) {
        Order order = orders.findById(state.orderId()).orElse(null);
        if (order == null) return;
        order.confirm();                // PLACED -> CONFIRMED
        orders.save(order);
    }

    private void shipOrder(OrchestrationState state) {
        Order order = orders.findById(state.orderId()).orElse(null);
        if (order == null) return;
        if (order.getStatus() == dev.dev48v.orderhub.domain.OrderStatus.PLACED) {
            order.confirm();            // defensive: confirm if payment step was skipped in an odd flow
        }
        order.ship();                   // CONFIRMED -> SHIPPED
        orders.save(order);
    }

    private void cancelOrder(OrchestrationState state, String reason) {
        Order order = orders.findById(state.orderId()).orElse(null);
        if (order != null) {
            order.cancel();             // -> CANCELLED (idempotent on the domain side)
            orders.save(order);
        }
        state.cancel(reason);           // latch the saga terminal AFTER mutating the order
    }

    // Build the next command for a saga: a fresh commandId, the order's data, and the given kind + amount.
    private SagaCommand command(OrchestrationState state, String type, BigDecimal amount) {
        return new SagaCommand(UUID.randomUUID().toString(), state.orderId(), type,
                state.item(), state.quantity(), state.customer(), amount, Instant.now());
    }

    // ── read surface for the OrchestrationController ─────────────────────────────────────────────────────
    public Collection<OrchestrationView> all() {
        return sagas.values().stream().map(OrchestrationView::from).toList();
    }

    public List<OrchestrationView> forOrder(String orderId) {
        OrchestrationState state = sagas.get(orderId);
        return state == null ? List.of() : List.of(OrchestrationView.from(state));
    }
}
