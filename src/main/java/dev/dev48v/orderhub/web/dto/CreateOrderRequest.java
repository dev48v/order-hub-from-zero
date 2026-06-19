package dev.dev48v.orderhub.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

// STEP 3 — The request DTO (what the client is allowed to send).
// WHY: never bind HTTP input straight onto your domain object. A DTO is the public
// contract + the validation boundary. The annotations are checked automatically when
// the controller marks the body @Valid, so malformed requests are rejected before any
// business code runs. A Java record gives us an immutable carrier for free.
public record CreateOrderRequest(
        @NotBlank(message = "customer is required") String customer,
        @NotBlank(message = "item is required") String item,
        @Positive(message = "quantity must be greater than 0") int quantity
) {}
