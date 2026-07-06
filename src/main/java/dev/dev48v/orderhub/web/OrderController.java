package dev.dev48v.orderhub.web;

import dev.dev48v.orderhub.domain.Order;
import dev.dev48v.orderhub.idempotency.Idempotent;
import dev.dev48v.orderhub.service.OrderService;
import dev.dev48v.orderhub.web.dto.CreateOrderRequest;
import dev.dev48v.orderhub.web.dto.OrderResponse;
import dev.dev48v.orderhub.web.dto.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

// STEP 8 — The controller: the thin HTTP layer.
// WHY: it only translates between HTTP and the service. @Valid triggers the DTO's
// validation (a bad body → 400 automatically). It maps domain objects to response
// DTOs and sets correct status codes (201 + Location on create). No business logic here.
@RestController
@RequestMapping("/api/orders")
// Day 10 — @Tag groups all these endpoints under one named section in Swagger UI.
@Tag(name = "Orders", description = "Create, fetch, list and confirm customer orders.")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    // Day 16 — @Idempotent makes this POST safe to retry. When the client sends an Idempotency-Key
    // header, IdempotencyAspect processes the FIRST request normally and remembers its response; any
    // repeat with the same key replays that exact response (same 201, same order) instead of creating
    // a second order — so a network retry, a double-click or a resumed mobile request can never place
    // a duplicate. With no key, the endpoint behaves exactly as before.
    @PostMapping
    @Idempotent
    @Operation(summary = "Place a new order",
            description = "Creates an order in the PLACED state and returns it with a Location header. "
                    + "Send an Idempotency-Key header to make retries safe: repeats with the same key "
                    + "replay the original response without creating a duplicate order.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Order created"),
            @ApiResponse(responseCode = "400", description = "Validation failed (RFC-7807 problem+json)"),
            @ApiResponse(responseCode = "409", description = "A request with the same Idempotency-Key is still in flight"),
            @ApiResponse(responseCode = "422", description = "The Idempotency-Key was reused with a different payload")
    })
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest req) {
        Order order = service.placeOrder(req.customer(), req.item(), req.quantity());
        return ResponseEntity
                .created(URI.create("/api/orders/" + order.getId()))   // 201 + Location header
                .body(OrderResponse.from(order));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an order by id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order found"),
            @ApiResponse(responseCode = "404", description = "No order with that id")
    })
    public OrderResponse get(
            @Parameter(description = "Order id", example = "a1b2c3d4") @PathVariable String id) {
        return OrderResponse.from(service.getOrder(id));
    }

    // Day 6 — list orders with pagination, sorting and an optional status filter.
    // WHY: ?status= is parsed in the service; Spring resolves ?page=&size=&sort= into the
    // Pageable automatically (e.g. ?page=1&size=10&sort=createdAt,desc). We map the
    // Page<Order> to a Page<OrderResponse> so the entity never leaks, then wrap it in our
    // own PagedResponse envelope (content + page metadata) for a stable API contract.
    //
    // Day 7 — only the default SORT lives here now. The default and maximum page SIZE are no
    // longer hard-coded: we hand the service the client's raw ?size= (null when omitted) and
    // it applies app.orders.default-page-size / max-page-size from OrderProperties. @PageableDefault
    // still resolves the page index and sort; its size is ignored because the service overrides it.
    @GetMapping
    @Operation(summary = "List orders (paged, sortable, optional status filter)",
            description = "Supports ?page=&size=&sort= (e.g. ?page=1&size=10&sort=createdAt,desc) "
                    + "and an optional ?status= filter. Returns a content + page-metadata envelope.")
    @ApiResponse(responseCode = "200", description = "Page of orders")
    public PagedResponse<OrderResponse> list(
            @Parameter(description = "Filter by status, e.g. PLACED or CONFIRMED", example = "PLACED")
            @RequestParam(required = false) String status,
            @Parameter(description = "Page size; capped by app.orders.max-page-size", example = "10")
            @RequestParam(name = "size", required = false) Integer size,
            @PageableDefault(sort = "createdAt") Pageable pageable) {
        Page<OrderResponse> page = service.list(status, size, pageable).map(OrderResponse::from);
        return PagedResponse.from(page);
    }

    @PostMapping("/{id}/confirm")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "Confirm a placed order",
            description = "Moves a PLACED order to CONFIRMED. Confirming an order that is not "
                    + "PLACED is a 409 Conflict.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Order confirmed"),
            @ApiResponse(responseCode = "404", description = "No order with that id"),
            @ApiResponse(responseCode = "409", description = "Order is not in a confirmable state")
    })
    public OrderResponse confirm(
            @Parameter(description = "Order id", example = "a1b2c3d4") @PathVariable String id) {
        return OrderResponse.from(service.confirmOrder(id));
    }
}
