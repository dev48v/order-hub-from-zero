package dev.dev48v.orderhub.domain;

// The lifecycle of an order. Today only PLACED is used; later phases
// (inventory reservation, payment, shipping via Kafka events) drive the rest.
public enum OrderStatus {
    PLACED,
    CONFIRMED,
    SHIPPED,
    CANCELLED
}
