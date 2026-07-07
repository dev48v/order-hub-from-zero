package dev.dev48v.inventory.web.dto;

import jakarta.validation.constraints.Positive;

// Day 17 — the body of a reservation request: how many units to hold. Bean Validation (@Positive)
// fails a zero/negative quantity fast at the HTTP edge with a 400, before it ever reaches the
// service — the same validation-at-the-boundary approach order-service takes on CreateOrderRequest.
public record ReserveRequest(
        @Positive(message = "quantity must be positive")
        int quantity
) {
}
