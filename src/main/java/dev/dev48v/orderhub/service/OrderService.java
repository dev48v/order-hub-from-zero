package dev.dev48v.orderhub.service;

import dev.dev48v.orderhub.domain.Order;
import dev.dev48v.orderhub.domain.OrderStatus;
import dev.dev48v.orderhub.repository.OrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    // Day 6 — a paged, sorted and optionally status-filtered listing.
    // WHY: the controller hands us the raw ?status= string and a Spring-resolved Pageable
    // (page/size/sort). Parsing the status here keeps the controller thin and the rule in one
    // place: blank/missing means "no filter"; an unrecognised value isn't an error — it simply
    // matches nothing, so we return an empty page rather than a 500. Then we delegate to the
    // repository port, which is identical across the JPA and in-memory backends.
    public Page<Order> list(String statusParam, Pageable pageable) {
        OrderStatus status = parseStatus(statusParam);
        if (statusParam != null && !statusParam.isBlank() && status == null) {
            // A value was supplied but didn't match any status → nothing matches.
            return Page.empty(pageable);
        }
        return repository.search(status, pageable);
    }

    // Lenient parse: null/blank → no filter (null); a valid name (case-insensitive) → that
    // status; anything else → null, which list() treats as "matches nothing".
    private OrderStatus parseStatus(String statusParam) {
        if (statusParam == null || statusParam.isBlank()) {
            return null;
        }
        try {
            return OrderStatus.valueOf(statusParam.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public Order confirmOrder(String id) {
        Order order = getOrder(id);
        order.confirm();              // the rule lives on the domain object
        return repository.save(order);
    }
}
