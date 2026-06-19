package dev.dev48v.orderhub.repository;

import dev.dev48v.orderhub.domain.Order;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

// STEP 6 — A throwaway in-memory implementation so Day 1 runs with zero infrastructure.
// WHY: @Repository makes Spring create + inject this wherever an OrderRepository is
// needed. A ConcurrentHashMap is thread-safe for the embedded server. Day 2 replaces
// this whole class with Spring Data JPA — the interface is the seam that makes that painless.
@Repository
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
}
