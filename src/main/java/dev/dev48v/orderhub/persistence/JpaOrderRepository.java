package dev.dev48v.orderhub.persistence;

import dev.dev48v.orderhub.domain.Order;
import dev.dev48v.orderhub.repository.OrderRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

// STEP — The real persistence adapter: the SAME OrderRepository port, now backed by JPA.
// WHY: the service depends on the OrderRepository interface, not on how rows are stored.
// Swapping the in-memory map for Postgres/H2 is just providing this implementation — the
// service and controller are untouched. That's the payoff of the layered/ports design.
//
// @Profile("!inmemory") makes this the DEFAULT bean. Activate the "inmemory" profile to
// fall back to the throwaway map (e.g. for a quick run without a database).
@Repository
@Profile("!inmemory")
public class JpaOrderRepository implements OrderRepository {

    private final SpringDataOrderRepository jpa;

    public JpaOrderRepository(SpringDataOrderRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    public Order save(Order order) {
        OrderEntity saved = jpa.save(OrderEntity.fromDomain(order));
        return saved.toDomain();
    }

    @Override
    public Optional<Order> findById(String id) {
        return jpa.findById(id).map(OrderEntity::toDomain);
    }

    @Override
    public List<Order> findAll() {
        return jpa.findAll().stream()
                .map(OrderEntity::toDomain)
                .toList();
    }
}
