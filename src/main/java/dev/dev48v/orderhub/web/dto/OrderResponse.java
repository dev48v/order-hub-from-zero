package dev.dev48v.orderhub.web.dto;

import dev.dev48v.orderhub.domain.Order;
import dev.dev48v.orderhub.domain.OrderStatus;

import java.time.Instant;

// STEP 4 — The response DTO (what we expose back to clients).
// WHY: a separate output shape means the domain can change without breaking the
// API contract, and you never accidentally leak internal fields. The static
// factory keeps the mapping in one place.
public record OrderResponse(
        String id,
        String customer,
        String item,
        int quantity,
        OrderStatus status,
        Instant createdAt
) {
    public static OrderResponse from(Order o) {
        return new OrderResponse(o.getId(), o.getCustomer(), o.getItem(),
                o.getQuantity(), o.getStatus(), o.getCreatedAt());
    }
}
