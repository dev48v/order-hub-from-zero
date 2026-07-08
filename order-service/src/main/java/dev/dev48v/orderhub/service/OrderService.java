package dev.dev48v.orderhub.service;

import dev.dev48v.orderhub.config.CacheConfig;
import dev.dev48v.orderhub.config.OrderProperties;
import dev.dev48v.orderhub.domain.Order;
import dev.dev48v.orderhub.domain.OrderStatus;
import dev.dev48v.orderhub.inventory.InventoryReservationException;
import dev.dev48v.orderhub.inventory.InventoryServiceClient;
import dev.dev48v.orderhub.inventory.ReserveRequest;
import dev.dev48v.orderhub.inventory.StockView;
import dev.dev48v.orderhub.repository.OrderRepository;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
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
//
// Day 11 — CACHING KEY STRATEGY (all wiring is in CacheConfig):
//   • cache "order"  — one entry PER ORDER, keyed by the order id (key = "#id"). getOrder(id)
//                      populates it (@Cacheable); confirmOrder(id) removes that one id
//                      (@CacheEvict key="#id"); placeOrder can't affect an existing id so it
//                      touches only the list cache.
//   • cache "orders" — the WHOLE list under one fixed key ("'all'"). listOrders() populates it;
//                      ANY write (place/confirm) evicts the entire "orders" cache so the next
//                      list read is rebuilt from the database. We use allEntries=true rather than
//                      a key because there is exactly one list snapshot and it's cheap to drop.
// Why evict rather than update: a stale read that lingers is far worse than an extra DB round-trip
// right after a write. Evicting is simple and always correct; the next reader repopulates the cache.
//
// Note: the PAGED list() below is intentionally NOT cached. Its result is a Spring Data Page whose
// concrete type doesn't round-trip cleanly through a JSON cache, and the cache key would have to
// fold in page/size/sort/status — a low-hit, high-churn combination. Caching the single-order read
// (the real hotspot) and the simple full-list read gives the win without that complexity.
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository repository;
    private final OrderProperties properties;
    // Day 18: the OpenFeign proxy for inventory-service. Injected like any bean; behind the interface it
    // makes a real HTTP call. This is where the two services actually talk over the wire.
    private final InventoryServiceClient inventory;

    public OrderService(OrderRepository repository,
                        OrderProperties properties,
                        InventoryServiceClient inventory) {
        this.repository = repository;
        this.properties = properties;
        this.inventory = inventory;
    }

    // A brand-new order can't already be in the "order" cache (its id was just minted), so there's
    // nothing per-id to evict here. But it DOES change the full list, so we drop the "orders" cache
    // so the next listOrders() rebuilds from the database and includes this new row.
    @CacheEvict(cacheNames = CacheConfig.ORDERS_CACHE, allEntries = true)
    public Order placeOrder(String customer, String item, int quantity) {
        // The DTO's @Max is a static fast-fail at the HTTP edge; this is the authoritative,
        // configurable business limit (app.orders.max-quantity). Keeping it here means the
        // rule holds no matter how an order is placed, and the ceiling is tunable per env.
        if (quantity > properties.maxQuantity()) {
            throw new IllegalArgumentException(
                    "quantity must be at most " + properties.maxQuantity());
        }
        // Day 18 — the inter-service call. Before we commit the order, reserve the stock in the
        // inventory-service over HTTP (via the OpenFeign client). We only persist the order once the
        // reservation succeeds, so we never accept an order we can't fulfil. The order's `item` is used
        // as the inventory SKU (e.g. "KEYBOARD-001"); a SKU the inventory service doesn't recognise, or
        // hasn't enough of, fails the reservation and therefore the order.
        reserveStock(item, quantity);
        Order order = new Order(UUID.randomUUID().toString(), customer, item, quantity);
        return repository.save(order);
    }

    // Day 18 — the synchronous reservation call to inventory-service, and how we handle its response.
    // The declarative Feign call reads like a local method invocation; underneath it's a POST to
    // inventory-service. Any non-2xx (404 unknown SKU, 409 insufficient stock, a 5xx) or a transport
    // failure (service down / unreachable) is raised by Feign as a FeignException, which we translate
    // into a domain InventoryReservationException — the web layer turns that into a 409. This is the
    // coupling of a synchronous call made explicit: order-service cannot proceed if the dependency won't.
    private void reserveStock(String sku, int quantity) {
        try {
            StockView stock = inventory.reserve(sku, new ReserveRequest(quantity));
            log.info("Reserved {} unit(s) of '{}' in inventory-service; {} remaining",
                    quantity, sku, stock != null ? stock.available() : "unknown");
        } catch (FeignException ex) {
            log.warn("Inventory reservation for '{}' x{} failed (HTTP {}): {}",
                    sku, quantity, ex.status(), ex.getMessage());
            throw new InventoryReservationException(sku, quantity, ex);
        }
    }

    // @Cacheable = cache-aside for a single order: check Redis under key "order::<id>" first; on a
    // HIT return the cached JSON without touching the database; on a MISS run the body and store the
    // returned Order before handing it back. unless="#result == null" is belt-and-braces — the
    // method throws on a miss rather than returning null, and the cache also refuses null values.
    @Cacheable(cacheNames = CacheConfig.ORDER_CACHE, key = "#id", unless = "#result == null")
    public Order getOrder(String id) {
        // Throw a domain exception, not an HTTP one. The service stays free of web
        // concerns; the @RestControllerAdvice turns this into a 404 ProblemDetail.
        return repository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    // Cache the full list under a single fixed key ("orders::all"). Any create/confirm evicts this
    // whole cache (see placeOrder/confirmOrder), so it can never serve a list that's missing a new
    // or newly-confirmed order.
    @Cacheable(cacheNames = CacheConfig.ORDERS_CACHE, key = "'all'")
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

    // A confirm mutates an existing order, so BOTH cache regions can now hold stale data:
    //   • the "order::<id>" entry still shows the old PLACED status → evict that exact key.
    //   • the "orders::all" list snapshot still shows the old status too → evict the whole list.
    // @Caching lets us stack the two @CacheEvicts. Note this method calls getOrder(id), but that
    // internal (self-)call bypasses the proxy, so it will NOT read from or write to the cache here —
    // it hits the repository directly, which is what we want on a write path.
    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.ORDER_CACHE, key = "#id"),
            @CacheEvict(cacheNames = CacheConfig.ORDERS_CACHE, allEntries = true)
    })
    public Order confirmOrder(String id) {
        Order order = getOrder(id);
        order.confirm();              // the rule lives on the domain object
        return repository.save(order);
    }
}
