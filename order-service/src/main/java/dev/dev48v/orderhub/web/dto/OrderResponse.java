package dev.dev48v.orderhub.web.dto;

import dev.dev48v.orderhub.domain.Order;
import dev.dev48v.orderhub.domain.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

// STEP 4 — The response DTO (what we expose back to clients).
// WHY: a separate output shape means the domain can change without breaking the
// API contract, and you never accidentally leak internal fields. The static
// factory keeps the mapping in one place.
@Schema(description = "An order as returned by the API.")
public record OrderResponse(
        @Schema(description = "Server-assigned order id.", example = "a1b2c3d4")
        String id,
        @Schema(description = "Customer who placed the order.", example = "Ada Lovelace")
        String customer,
        @Schema(description = "The item ordered.", example = "Mechanical keyboard")
        String item,
        @Schema(description = "Units ordered.", example = "2")
        int quantity,
        @Schema(description = "Current order status.", example = "PLACED")
        OrderStatus status,
        @Schema(description = "When the order was created (UTC).", example = "2026-06-30T10:15:30Z")
        Instant createdAt
) {
    public static OrderResponse from(Order o) {
        return new OrderResponse(o.getId(), o.getCustomer(), o.getItem(),
                o.getQuantity(), o.getStatus(), o.getCreatedAt());
    }
}
