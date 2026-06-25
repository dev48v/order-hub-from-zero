package dev.dev48v.orderhub.repository;

import dev.dev48v.orderhub.domain.Order;
import dev.dev48v.orderhub.domain.OrderStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

// STEP 6 — A throwaway in-memory implementation so Day 1 runs with zero infrastructure.
// WHY: @Repository makes Spring create + inject this wherever an OrderRepository is
// needed. A ConcurrentHashMap is thread-safe for the embedded server.
//
// Day 2: the JPA adapter is now the DEFAULT OrderRepository. This map is gated behind
// the "inmemory" profile so exactly one OrderRepository bean is active at a time. Run
// with --spring.profiles.active=inmemory to opt back into the zero-infrastructure mode.
@Repository
@Profile("inmemory")
public class InMemoryOrderRepository implements OrderRepository {

    private final Map<String, Order> store = new ConcurrentHashMap<>();

    @Override
    public Order save(Order order) {
        store.put(order.getId(), order);
        return order;
    }

    @Override
    public Optional<Order> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Order> findAll() {
        return List.copyOf(store.values());
    }

    // Day 6 — the same paged/filtered search the JPA adapter offers, done by hand over the map.
    // WHY: the JPA adapter gets paging from the database for free; the in-memory profile has to
    // reproduce the contract itself so the service/controller behave identically on either backend.
    // We (1) filter by status when one is given, (2) apply the Pageable's sort, then (3) slice the
    // requested page with offset/limit, and wrap it in a PageImpl that carries the FULL filtered
    // count so totalElements/totalPages stay correct.
    @Override
    public Page<Order> search(OrderStatus statusOrNull, Pageable pageable) {
        List<Order> filtered = store.values().stream()
                .filter(o -> statusOrNull == null || o.getStatus() == statusOrNull)
                .sorted(comparatorFor(pageable.getSort()))
                .toList();

        int total = filtered.size();
        int from = (int) Math.min(pageable.getOffset(), total);
        int to = Math.min(from + pageable.getPageSize(), total);
        List<Order> content = filtered.subList(from, to);

        return new PageImpl<>(content, pageable, total);
    }

    // Builds a Comparator from the Pageable's Sort, honouring each property + direction.
    // Unknown/unsupported properties are skipped; an empty sort keeps insertion-neutral order.
    private Comparator<Order> comparatorFor(Sort sort) {
        Comparator<Order> comparator = null;
        for (Sort.Order order : sort) {
            Comparator<Order> next = propertyComparator(order.getProperty());
            if (next == null) {
                continue;
            }
            if (order.isDescending()) {
                next = next.reversed();
            }
            comparator = (comparator == null) ? next : comparator.thenComparing(next);
        }
        // Stable fallback so paging is deterministic even without (or after an unknown) sort.
        Comparator<Order> byId = Comparator.comparing(Order::getId);
        return (comparator == null) ? byId : comparator.thenComparing(byId);
    }

    // Maps a sort property name to a comparator over the matching Order field.
    private Comparator<Order> propertyComparator(String property) {
        return switch (property) {
            case "id" -> Comparator.comparing(Order::getId);
            case "customer" -> Comparator.comparing(Order::getCustomer);
            case "item" -> Comparator.comparing(Order::getItem);
            case "quantity" -> Comparator.comparingInt(Order::getQuantity);
            case "status" -> Comparator.comparing(Order::getStatus);
            case "createdAt" -> Comparator.comparing(Order::getCreatedAt);
            default -> null;
        };
    }
}
