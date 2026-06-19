package dev.dev48v.orderhub.repository;

import dev.dev48v.orderhub.domain.Order;

import java.util.List;
import java.util.Optional;

// STEP 5 — The repository as an INTERFACE.
// WHY: the service depends on this abstraction, not on how orders are stored.
// Today it's an in-memory map; on Day 2 we swap in a JPA/Postgres implementation
// and nothing above this line changes. That's the payoff of the layered design.
public interface OrderRepository {
    Order save(Order order);
    Optional<Order> findById(String id);
    List<Order> findAll();
}
