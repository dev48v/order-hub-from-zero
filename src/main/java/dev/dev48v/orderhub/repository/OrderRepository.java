package dev.dev48v.orderhub.repository;

import dev.dev48v.orderhub.domain.Order;
import dev.dev48v.orderhub.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

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

    // Day 6 — a paged, sorted and optionally status-filtered slice of the orders.
    // WHY: returning every row never scales. Spring Data's Page<T>/Pageable pair carries
    // the slice of content plus the metadata (which page, total elements/pages) the client
    // needs to navigate. A null status means "don't filter"; the Pageable supplies the
    // page index, size and sort. The port speaks the domain (Order) and these two neutral
    // Spring Data value types, so both the JPA and in-memory adapters can honour it.
    Page<Order> search(OrderStatus statusOrNull, Pageable pageable);
}
