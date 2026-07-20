package dev.dev48v.orderhub.orchestration;

import java.math.BigDecimal;
import java.time.Instant;

// Day 29 — a read-only projection of an OrchestrationState for the HTTP surface (OrchestrationController).
// OrchestrationState is package-private and mutable; this immutable record is the safe, JSON-friendly window
// onto it, so the coordinator's per-order progress (which state it is in, whether it completed or was
// compensated) is observable directly — `curl localhost:8082/api/orchestration` — mirroring Day 28's SagaView.
public record OrchestrationView(
        String orderId,
        String step,            // STARTED | AWAITING_STOCK | AWAITING_PAYMENT | AWAITING_SHIPMENT | COMPLETED | CANCELLED
        boolean stockReserved,  // has inventory reserved (and thus would need releasing on compensation)?
        BigDecimal amount,      // the charged amount once payment approved, else null
        boolean terminal,       // has the saga reached a terminal state?
        String result,          // COMPLETED | CANCELLED | null (still running)
        String reason,          // why cancelled, else null
        Instant startedAt
) {

    static OrchestrationView from(OrchestrationState s) {
        return new OrchestrationView(
                s.orderId(),
                s.step().name(),
                s.stockReserved(),
                s.amount(),
                s.isTerminal(),
                s.result(),
                s.reason(),
                s.startedAt());
    }
}
