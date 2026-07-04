package dev.dev48v.orderhub.inventory;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

// Day 14 — proves the circuit breaker actually TRANSITIONS and diverts to the fallback.
// WHY a plain unit test (no @SpringBootTest, no *IT / Testcontainers / Docker): the behaviour under
// test is Resilience4j's state machine, not the wiring. We build a CircuitBreaker with the SAME shape
// as application.yml's "inventory" instance, then drive the real InventoryClient stub through it and
// assert the state moves CLOSED → OPEN → (after the wait) HALF_OPEN → CLOSED. Keeping it in the
// surefire suite (a *Test class) means it runs on every `mvn test` with no infrastructure — unlike
// the *IT integration tests, which failsafe only runs under `mvn verify` and which need Docker for
// their Postgres/Redis containers.
//
// We decorate the client's checkStock() with the breaker MANUALLY here (breaker.decorateSupplier)
// rather than relying on the @CircuitBreaker aspect, because the aspect only weaves inside a Spring
// context. Same breaker, same semantics — this just lets us assert transitions deterministically
// with a tiny, controllable window.
@DisplayName("Resilience4j circuit breaker: state transitions + fallback")
class CircuitBreakerTest {

    private InventoryClient client;
    private CircuitBreaker breaker;

    // A tiny, deterministic breaker: window of 5 calls, trips at a 50% failure rate, evaluated once
    // 5 calls are in, then stays OPEN for a very short 200ms so the test can observe recovery quickly.
    @BeforeEach
    void setUp() {
        client = new InventoryClient();

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(5)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofMillis(200))
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordExceptions(InventoryUnavailableException.class)
                .build();

        breaker = CircuitBreakerRegistry.of(config).circuitBreaker("inventory");
    }

    // Run checkStock through the breaker, falling back to the client's degraded answer exactly as the
    // @CircuitBreaker(fallbackMethod=...) does in production: on a short-circuit (CallNotPermitted
    // while OPEN) OR a real failure, return the degraded status instead of letting the error escape.
    private InventoryStatus call(String item) {
        try {
            return breaker.decorateSupplier(() -> client.checkStock(item)).get();
        } catch (Exception ex) {
            return InventoryStatus.degraded(item);
        }
    }

    @Test
    @DisplayName("A healthy downstream keeps the breaker CLOSED and returns live answers")
    void healthyDownstreamStaysClosed() {
        client.setFailureRate(0.0);

        for (int i = 0; i < 10; i++) {
            InventoryStatus status = call("Keyboard");
            assertThat(status.degraded()).isFalse();
            assertThat(status.source()).isEqualTo("inventory-service");
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("Enough failures trip the breaker OPEN and calls are short-circuited to the fallback")
    void failuresTripBreakerOpenAndUseFallback() {
        // Downstream is fully broken: every real call throws.
        client.setFailureRate(1.0);

        // Fill the window (5 calls) with failures → failure rate 100% ≥ 50% → OPEN.
        for (int i = 0; i < 5; i++) {
            call("Keyboard");
        }
        assertThat(breaker.getState())
                .as("5 consecutive failures over a window of 5 should open the breaker")
                .isEqualTo(CircuitBreaker.State.OPEN);

        // While OPEN, the real downstream is NOT called — further calls are short-circuited straight
        // to the fallback. We prove that by resetting the stub's counter and confirming it stays 0.
        client.resetCallCount();
        for (int i = 0; i < 5; i++) {
            InventoryStatus status = call("Keyboard");
            // The caller still gets a graceful degraded answer, never an exception / 500.
            assertThat(status.degraded()).isTrue();
            assertThat(status.source()).isEqualTo("fallback");
        }
        assertThat(client.callCount())
                .as("while OPEN the breaker short-circuits — the downstream stub is never invoked")
                .isZero();
    }

    @Test
    @DisplayName("After the wait, the breaker goes HALF_OPEN and CLOSES again once trial calls succeed")
    void breakerRecoversThroughHalfOpen() throws InterruptedException {
        // Trip it OPEN first.
        client.setFailureRate(1.0);
        for (int i = 0; i < 5; i++) {
            call("Keyboard");
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Downstream recovers; wait out the OPEN cooldown so the breaker will permit trial calls.
        client.setFailureRate(0.0);
        Thread.sleep(300); // > waitDurationInOpenState (200ms)

        // The permitted HALF_OPEN trial calls (2) all succeed → the breaker CLOSES.
        InventoryStatus first = call("Keyboard");
        InventoryStatus second = call("Keyboard");
        assertThat(first.degraded()).isFalse();
        assertThat(second.degraded()).isFalse();
        assertThat(breaker.getState())
                .as("successful trial calls in HALF_OPEN should close the breaker")
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
