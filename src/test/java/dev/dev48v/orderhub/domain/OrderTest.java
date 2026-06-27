package dev.dev48v.orderhub.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Day 8 — a plain JUnit 5 unit test for the domain object.
// WHY: the one rule the Order owns (you can only confirm a PLACED order) lives entirely
// in this class, with no Spring, web or database involved. So the cheapest, fastest test
// is a direct one: build an Order, call its method, assert the outcome. No context to boot.
class OrderTest {

    @Test
    @DisplayName("new order starts PLACED with a createdAt timestamp")
    void newOrderHasSaneDefaults() {
        Instant before = Instant.now();
        Order order = new Order("id-1", "Ada", "Keyboard", 2);

        assertThat(order.getId()).isEqualTo("id-1");
        assertThat(order.getCustomer()).isEqualTo("Ada");
        assertThat(order.getItem()).isEqualTo("Keyboard");
        assertThat(order.getQuantity()).isEqualTo(2);
        // The public constructor always begins a brand-new order.
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PLACED);
        // createdAt is stamped at construction time, so it is set and not in the future.
        assertThat(order.getCreatedAt()).isNotNull();
        assertThat(order.getCreatedAt()).isBetween(before, Instant.now());
    }

    @Test
    @DisplayName("confirm() moves a PLACED order to CONFIRMED")
    void confirmMovesPlacedToConfirmed() {
        Order order = new Order("id-2", "Babbage", "Mouse", 1);

        order.confirm();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("confirming a non-PLACED order throws IllegalStateException")
    void confirmTwiceIsRejected() {
        Order order = new Order("id-3", "Lovelace", "Monitor", 1);
        order.confirm(); // now CONFIRMED

        // A second confirm violates the rule: only a PLACED order can be confirmed.
        assertThatThrownBy(order::confirm)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PLACED");

        // The failed transition must not have mutated the state.
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
    }

    @Test
    @DisplayName("a rehydrated non-PLACED order also cannot be confirmed")
    void rehydratedConfirmedOrderCannotBeConfirmed() {
        Order shipped = Order.rehydrate(
                "id-4", "Turing", "Cable", 3, OrderStatus.SHIPPED, Instant.now());

        assertThatThrownBy(shipped::confirm)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHIPPED");
    }
}
