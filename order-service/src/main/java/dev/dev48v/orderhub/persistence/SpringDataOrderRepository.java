package dev.dev48v.orderhub.persistence;

import dev.dev48v.orderhub.domain.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

// STEP — Spring Data JPA generates the implementation of this interface at runtime.
// WHY: extending JpaRepository<OrderEntity, String> gives us save/findById/findAll
// (and much more) for free against the "orders" table — no SQL, no boilerplate.
// This works on entities; the adapter (JpaOrderRepository) translates to/from the domain.
public interface SpringDataOrderRepository extends JpaRepository<OrderEntity, String> {

    // Day 6 — a derived query: Spring Data parses the method name and writes the SQL
    // (WHERE status = ? plus the page/sort from the Pageable) for us. The inherited
    // findAll(Pageable) covers the unfiltered case, so the adapter only needs this one.
    Page<OrderEntity> findByStatus(OrderStatus status, Pageable pageable);
}
