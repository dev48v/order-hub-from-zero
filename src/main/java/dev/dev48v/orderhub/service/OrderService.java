package dev.dev48v.orderhub.service;

import dev.dev48v.orderhub.config.OrderProperties;
import dev.dev48v.orderhub.domain.Order;
import dev.dev48v.orderhub.domain.OrderStatus;
import dev.dev48v.orderhub.repository.OrderRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

// STEP 7 — The service layer: where business logic lives.
// WHY: controllers should stay thin (HTTP in/out) and repositories dumb (storage).
// The service is the middle that orchestrates them and owns the rules. Constructor
// injection (no @Autowired needed) keeps it testable — pass a fake repository in a unit test.
//
// Day 7: the tunable limits (max quantity, page sizes) no longer live as magic numbers
// here — they come from the injected OrderProperties, so they can be changed per
// environment via config or env vars without recompiling.
@Service
public class OrderService {

    private final OrderRepository repository;
    private final OrderProperties properties;

    public OrderService(OrderRepository repository, OrderProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public Order placeOrder(String customer, String item, int quantity) {
        // The DTO's @Max is a static fast-fail at the HTTP edge; this is the authoritative,
        // configurable business limit (app.orders.max-quantity). Keeping it here means the
        // rule holds no matter how an order is placed, and the ceiling is tunable per env.
        if (quantity > properties.maxQuantity()) {
            throw new IllegalArgumentException(
                    "quantity must be at most " + properties.maxQuantity());
        }
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
    //
    // Day 7 — requestedSize is the client's raw ?size= (null when omitted). The paging bounds
    // now come from OrderProperties: a missing/invalid size defaults to defaultPageSize, and
    // anything above maxPageSize is capped — so the page/sort still flow through the Pageable
    // but the SIZE is governed entirely by externalised config.
    public Page<Order> list(String statusParam, Integer requestedSize, Pageable pageable) {
        Pageable effective = applyPagingBounds(requestedSize, pageable);
        OrderStatus status = parseStatus(statusParam);
        if (statusParam != null && !statusParam.isBlank() && status == null) {
            // A value was supplied but didn't match any status → nothing matches.
            return Page.empty(effective);
        }
        return repository.search(status, effective);
    }

    // Day 7 — resolve the effective page size from the configured bounds (app.orders.*).
    // A null/non-positive requested size falls back to defaultPageSize; any larger request is
    // capped at maxPageSize so a single call can never pull an unbounded slice. Page index and
    // sort are taken from the Spring-resolved Pageable unchanged.
    private Pageable applyPagingBounds(Integer requestedSize, Pageable pageable) {
        int size = (requestedSize == null || requestedSize <= 0)
                ? properties.defaultPageSize()
                : Math.min(requestedSize, properties.maxPageSize());
        return PageRequest.of(pageable.getPageNumber(), size, pageable.getSort());
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
