package dev.dev48v.orderhub.web;

import dev.dev48v.orderhub.domain.Order;
import dev.dev48v.orderhub.service.OrderService;
import dev.dev48v.orderhub.web.dto.CreateOrderRequest;
import dev.dev48v.orderhub.web.dto.OrderResponse;
import dev.dev48v.orderhub.web.dto.PagedResponse;
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
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> create(@Valid @RequestBody CreateOrderRequest req) {
        Order order = service.placeOrder(req.customer(), req.item(), req.quantity());
        return ResponseEntity
                .created(URI.create("/api/orders/" + order.getId()))   // 201 + Location header
                .body(OrderResponse.from(order));
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable String id) {
        return OrderResponse.from(service.getOrder(id));
    }

    // Day 6 — list orders with pagination, sorting and an optional status filter.
    // WHY: ?status= is parsed in the service; Spring resolves ?page=&size=&sort= into the
    // Pageable automatically (e.g. ?page=1&size=10&sort=createdAt,desc). @PageableDefault
    // gives sane defaults when the client sends nothing. We map the Page<Order> to a
    // Page<OrderResponse> so the entity never leaks, then wrap it in our own PagedResponse
    // envelope (content + page metadata) for a stable API contract.
    @GetMapping
    public PagedResponse<OrderResponse> list(
            @RequestParam(required = false) String status,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        Page<OrderResponse> page = service.list(status, pageable).map(OrderResponse::from);
        return PagedResponse.from(page);
    }

    @PostMapping("/{id}/confirm")
    @ResponseStatus(HttpStatus.OK)
    public OrderResponse confirm(@PathVariable String id) {
        return OrderResponse.from(service.confirmOrder(id));
    }
}
