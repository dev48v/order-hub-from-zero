package dev.dev48v.orderhub.web;

import dev.dev48v.orderhub.domain.Order;
import dev.dev48v.orderhub.service.OrderService;
import dev.dev48v.orderhub.web.dto.CreateOrderRequest;
import dev.dev48v.orderhub.web.dto.OrderResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

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

    @GetMapping
    public List<OrderResponse> list() {
        return service.listOrders().stream().map(OrderResponse::from).toList();
    }

    @PostMapping("/{id}/confirm")
    @ResponseStatus(HttpStatus.OK)
    public OrderResponse confirm(@PathVariable String id) {
        return OrderResponse.from(service.confirmOrder(id));
    }
}
