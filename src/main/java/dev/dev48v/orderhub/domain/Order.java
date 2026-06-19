package dev.dev48v.orderhub.domain;

import java.time.Instant;

// STEP 2 — The domain model: a plain Java object the rest of the app revolves around.
// WHY: keep the domain free of web/database concerns for now. It's just data + the
// one rule it owns (you can only confirm an order that's still PLACED). In Day 52 this
// becomes a JPA @Entity, but the layered design means the controller/service won't care.
public class Order {

    private final String id;
    private final String customer;
    private final String item;
    private final int quantity;
    private OrderStatus status;
    private final Instant createdAt;

    public Order(String id, String customer, String item, int quantity) {
        this.id = id;
        this.customer = customer;
        this.item = item;
        this.quantity = quantity;
        this.status = OrderStatus.PLACED;
        this.createdAt = Instant.now();
    }

    // A tiny piece of business behaviour that belongs to the order itself.
    public void confirm() {
        if (status != OrderStatus.PLACED) {
            throw new IllegalStateException("Only a PLACED order can be confirmed (was " + status + ")");
        }
        this.status = OrderStatus.CONFIRMED;
    }

    public String getId() { return id; }
    public String getCustomer() { return customer; }
    public String getItem() { return item; }
    public int getQuantity() { return quantity; }
    public OrderStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
