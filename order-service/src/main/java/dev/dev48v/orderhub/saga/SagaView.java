package dev.dev48v.orderhub.saga;

import java.math.BigDecimal;
import java.time.Instant;

// Day 28 — a read-only projection of a SagaState for the HTTP surface (SagaController). SagaState is
// package-private and mutable; this immutable record is the safe, JSON-friendly window onto it, so the
// saga's progress (which legs have answered, and the terminal decision) is observable directly —
// `curl localhost:8082/api/saga` — and demoable, exactly like payment-service's PaymentView.
public record SagaView(
        String orderId,
        String stockOutcome,    // RESERVED | INSUFFICIENT_STOCK | UNKNOWN_SKU | null (not yet)
        String paymentOutcome,  // APPROVED | DECLINED | null (not yet)
        BigDecimal amount,      // charged amount once payment answered, else null
        boolean terminal,       // has the saga decided?
        String result,          // SHIPPED | CANCELLED | null (still running)
        String reason,          // why cancelled, else null
        Instant startedAt
) {

    static SagaView from(SagaState s) {
        return new SagaView(
                s.orderId(),
                s.stockOutcome(),
                s.paymentOutcome(),
                s.amount(),
                s.isTerminal(),
                s.result(),
                s.reason(),
                s.startedAt());
    }
}
