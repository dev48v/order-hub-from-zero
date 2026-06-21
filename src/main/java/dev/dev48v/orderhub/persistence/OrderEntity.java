package dev.dev48v.orderhub.persistence;

import dev.dev48v.orderhub.domain.Order;
import dev.dev48v.orderhub.domain.OrderStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

// STEP — The JPA mapping for an order, kept OUT of the domain on purpose.
// WHY: Order stays a plain, framework-free domain object. This entity is the
// "database shape" of an order and lives only in the persistence layer. The two
// mappers below (fromDomain / toDomain) bridge between the pure domain and Hibernate,
// so the storage technology never leaks upward into the service or controller.
@Entity
@Table(name = "orders")
public class OrderEntity {

    @Id
    private String id;
    private String customer;
    private String item;
    private int quantity;

    // Store the enum by name (PLACED, CONFIRMED, ...) rather than ordinal, so reordering
    // the enum later can't silently corrupt existing rows.
    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    private Instant createdAt;

    // JPA requires a no-arg constructor to instantiate entities when loading rows.
    protected OrderEntity() {
    }

    // Domain -> entity: what we persist when saving.
    public static OrderEntity fromDomain(Order o) {
        OrderEntity e = new OrderEntity();
        e.id = o.getId();
        e.customer = o.getCustomer();
        e.item = o.getItem();
        e.quantity = o.getQuantity();
        e.status = o.getStatus();
        e.createdAt = o.getCreatedAt();
        return e;
    }

    // Entity -> domain: rebuild a real Order (with its saved status + createdAt) on read.
    public Order toDomain() {
        return Order.rehydrate(id, customer, item, quantity, status, createdAt);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getCustomer() { return customer; }
    public void setCustomer(String customer) { this.customer = customer; }

    public String getItem() { return item; }
    public void setItem(String item) { this.item = item; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
