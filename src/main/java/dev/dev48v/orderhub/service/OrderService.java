package dev.dev48v.orderhub.service;

import dev.dev48v.orderhub.domain.Order;
import dev.dev48v.orderhub.repository.OrderRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

// STEP 7 — The service layer: where business logic lives.
// WHY: controllers should stay thin (HTTP in/out) and repositories dumb (storage).
// The service is the middle that orchestrates them and owns the rules. Constructor
// injection (no @Autowired needed) keeps it testable — pass a fake repository in a unit test.
@Service
public class OrderService {

    private final OrderRepository repository;

    public OrderService(OrderRepository repository) {
        this.repository = repository;
    }

    public Order placeOrder(String customer, String item, int quantity) {
        Order order = new Order(UUID.randomUUID().toString(), customer, item, quantity);
        return repository.save(order);
    }

    public Order getOrder(String id) {
        // Throw a domain exception, not an HTTP one. The service stays free of web
        // concerns; the @RestControllerAdvice turns this into a 404 ProblemDetail.
        return repository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    public List<Order> listOrders() {
        return repository.findAll();
    }

    public Order confirmOrder(String id) {
        Order order = getOrder(id);
        order.confirm();              // the rule lives on the domain object
        return repository.save(order);
    }
}
