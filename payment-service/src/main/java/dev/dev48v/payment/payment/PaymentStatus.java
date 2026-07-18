package dev.dev48v.payment.payment;

// Day 27 — the lifecycle of a payment. A Payment starts PENDING (created, not yet decided) and moves ONCE
// to a terminal state: APPROVED or DECLINED. Modeling it as an enum with a terminal transition keeps the
// finance-safe invariant explicit — a payment is decided exactly once, never flipped afterwards (Payment
// enforces that in approve()/decline()).
public enum PaymentStatus {
    PENDING,
    APPROVED,
    DECLINED
}
