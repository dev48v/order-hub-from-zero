package dev.dev48v.payment.web.dto;

import dev.dev48v.payment.payment.Payment;

import java.math.BigDecimal;
import java.time.Instant;

// Day 27 — the API response shape for a recorded payment. A DTO separate from the Payment domain object
// (the same request/response-separation discipline the other services use): the wire contract can evolve
// independently of the internal model, and we choose exactly which fields to expose.
public record PaymentView(
        String paymentId,
        String orderId,
        String customer,
        BigDecimal amount,
        String status,
        String reason,
        Instant createdAt) {

    public static PaymentView from(Payment p) {
        return new PaymentView(p.getPaymentId(), p.getOrderId(), p.getCustomer(),
                p.getAmount(), p.getStatus().name(), p.getReason(), p.getCreatedAt());
    }
}
