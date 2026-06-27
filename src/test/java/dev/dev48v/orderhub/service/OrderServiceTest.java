package dev.dev48v.orderhub.service;

import dev.dev48v.orderhub.config.OrderProperties;
import dev.dev48v.orderhub.domain.Order;
import dev.dev48v.orderhub.domain.OrderStatus;
import dev.dev48v.orderhub.repository.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Day 8 — a unit test for the service with the repository mocked out.
// WHY: the service owns the orchestration (look up, apply the rule, save) but it should be
// tested without a real database. Mockito stands in a fake OrderRepository so we can dictate
// what findById/save return and then verify the service made the right calls. OrderProperties
// is a plain record, so we build a real one with known limits rather than mocking it.
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository repository;

    private OrderService service;

    @BeforeEach
    void setUp() {
        // A record carries no behaviour to mock — construct it directly with explicit
        // limits (maxQuantity, defaultPageSize, maxPageSize) so the test is self-contained.
        OrderProperties properties = new OrderProperties(1000, 20, 100);
        service = new OrderService(repository, properties);
    }

    @Test
    @DisplayName("placeOrder saves the order and returns it")
    void placeOrderSavesAndReturns() {
        // save() echoes back whatever it is handed, mirroring a real persist.
        when(repository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        Order result = service.placeOrder("Ada", "Keyboard", 2);

        assertThat(result).isNotNull();
        assertThat(result.getCustomer()).isEqualTo("Ada");
        assertThat(result.getItem()).isEqualTo("Keyboard");
        assertThat(result.getQuantity()).isEqualTo(2);
        assertThat(result.getStatus()).isEqualTo(OrderStatus.PLACED);

        // Verify the service actually persisted, and inspect what it tried to save.
        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getCustomer()).isEqualTo("Ada");
        assertThat(saved.getValue().getId()).isNotBlank();
    }

    @Test
    @DisplayName("placeOrder above the configured max quantity is rejected before saving")
    void placeOrderOverMaxQuantityIsRejected() {
        assertThatThrownBy(() -> service.placeOrder("Ada", "Pallet", 1001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1000");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("getOrder on a missing id throws OrderNotFoundException")
    void getOrderMissingThrowsNotFound() {
        when(repository.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOrder("nope"))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining("nope");
    }

    @Test
    @DisplayName("confirmOrder on a missing id throws OrderNotFoundException")
    void confirmOrderMissingThrowsNotFound() {
        when(repository.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmOrder("ghost"))
                .isInstanceOf(OrderNotFoundException.class)
                .hasMessageContaining("ghost");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("confirmOrder confirms a PLACED order and saves it")
    void confirmOrderHappyPath() {
        Order placed = new Order("id-1", "Babbage", "Mouse", 1);
        when(repository.findById("id-1")).thenReturn(Optional.of(placed));
        when(repository.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        Order result = service.confirmOrder("id-1");

        assertThat(result.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
        verify(repository).save(placed);
    }

    @Test
    @DisplayName("confirmOrder on an already-confirmed order surfaces IllegalStateException")
    void confirmAlreadyConfirmedSurfacesIllegalState() {
        // Rehydrate an order that is already CONFIRMED so the domain rule trips.
        Order confirmed = Order.rehydrate(
                "id-2", "Lovelace", "Monitor", 1, OrderStatus.CONFIRMED, Instant.now());
        when(repository.findById("id-2")).thenReturn(Optional.of(confirmed));

        assertThatThrownBy(() -> service.confirmOrder("id-2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CONFIRMED");

        // The illegal transition must not have been persisted.
        verify(repository, never()).save(any());
    }
}
