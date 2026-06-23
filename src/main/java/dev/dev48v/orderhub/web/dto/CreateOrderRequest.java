package dev.dev48v.orderhub.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// STEP 3 — The request DTO (what the client is allowed to send).
// WHY: never bind HTTP input straight onto your domain object. A DTO is the public
// contract + the validation boundary. The annotations are checked automatically when
// the controller marks the body @Valid, so malformed requests are rejected before any
// business code runs. A Java record gives us an immutable carrier for free.
//
// Day 4: the constraints are deliberately thorough — every field has both a presence
// check and a bound, each with a clear message, so a bad request comes back as a
// precise 400 instead of blowing up deeper in the stack (or silently persisting junk).
// quantity is a primitive int, so it can never be null — @Min/@Max are enough.
public record CreateOrderRequest(
        @NotBlank(message = "customer is required")
        @Size(max = 120, message = "customer must be at most 120 characters")
        String customer,

        @NotBlank(message = "item is required")
        @Size(max = 200, message = "item must be at most 200 characters")
        String item,

        @Min(value = 1, message = "quantity must be at least 1")
        @Max(value = 1000, message = "quantity must be at most 1000")
        int quantity
) {}
