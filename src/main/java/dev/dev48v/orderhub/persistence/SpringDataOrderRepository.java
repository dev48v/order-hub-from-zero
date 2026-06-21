package dev.dev48v.orderhub.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

// STEP — Spring Data JPA generates the implementation of this interface at runtime.
// WHY: extending JpaRepository<OrderEntity, String> gives us save/findById/findAll
// (and much more) for free against the "orders" table — no SQL, no boilerplate.
// This works on entities; the adapter (JpaOrderRepository) translates to/from the domain.
public interface SpringDataOrderRepository extends JpaRepository<OrderEntity, String> {
}
