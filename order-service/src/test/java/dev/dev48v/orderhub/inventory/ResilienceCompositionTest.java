package dev.dev48v.orderhub.inventory;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

// Day 15 — proves the COMPOSITION ORDER we chose (Retry OUTSIDE CircuitBreaker) behaves as documented.
// The order isn't cosmetic: it decides whether retries feed the breaker or hide from it, and whether an
// OPEN breaker makes a composed call fail fast or keep hammering the dependency. We decorate the two
// primitives in the exact nesting Resilience4j applies by default — Retry ( CircuitBreaker ( call ) ) —
// and assert both consequences.
@DisplayName("Resilience4j composition: Retry wraps CircuitBreaker (order matters)")
class ResilienceCompositionTest {

    // Retry OUTSIDE the breaker: run the guarded call through the breaker first, then wrap that in retry.
    private Supplier<InventoryStatus> compose(Retry retry, CircuitBreaker breaker, InventoryClient client, String item) {
        Supplier<InventoryStatus> guarded = CircuitBreaker.decorateSupplier(breaker, () -> client.checkStock(item));
        return Retry.decorateSupplier(retry, guarded);
    }

    @Test
    @DisplayName("Each retry attempt is a fresh pass through the breaker — all attempts are recorded")
    void retryOutsideBreakerRecordsEveryAttempt() {
        InventoryClient client = new InventoryClient();
        client.setFailureRate(1.0); // every call throws InventoryUnavailableException

        // A window big enough that it won't TRIP during our 3 attempts — we only want to count them.
        CircuitBreaker breaker = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(100)
                .minimumNumberOfCalls(100)
                .recordExceptions(InventoryUnavailableException.class)
                .build()).circuitBreaker("inventory");

        Retry retry = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .retryExceptions(InventoryUnavailableException.class)
                .build()).retry("inventory");

        try {
            compose(retry, breaker, client, "Keyboard").get();
        } catch (Exception expected) {
            // retries exhausted → exception escapes (the real app's @Retry fallback would catch it)
        }

        assertThat(client.callCount())
                .as("the downstream was actually called once per attempt")
                .isEqualTo(3);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls())
                .as("Retry is OUTSIDE the breaker → all 3 attempts land in the breaker's window "
                        + "(were Retry inside, the breaker would see only 1 call)")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("With the breaker OPEN, the composed call fails fast — CallNotPermitted is not retried")
    void openBreakerFailsFastWithoutHammering() {
        InventoryClient client = new InventoryClient();

        // A tiny breaker that trips after 4 failures.
        CircuitBreaker breaker = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .recordExceptions(InventoryUnavailableException.class)
                .build()).circuitBreaker("inventory");

        // Retry IGNORES CallNotPermittedException — mirrors application.yml, so an OPEN breaker isn't retried.
        Retry retry = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .retryExceptions(InventoryUnavailableException.class)
                .ignoreExceptions(CallNotPermittedException.class)
                .build()).retry("inventory");

        // Trip the breaker OPEN by driving failures straight through it.
        client.setFailureRate(1.0);
        for (int i = 0; i < 4; i++) {
            try {
                CircuitBreaker.decorateSupplier(breaker, () -> client.checkStock("Keyboard")).get();
            } catch (Exception ignored) {
            }
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Now a COMPOSED call while OPEN: the downstream must NOT be touched, and it must NOT be retried.
        client.resetCallCount();
        boolean shortCircuited = false;
        try {
            compose(retry, breaker, client, "Keyboard").get();
        } catch (CallNotPermittedException notPermitted) {
            shortCircuited = true; // the real app's fallback returns degraded here
        }

        assertThat(shortCircuited)
                .as("an OPEN breaker short-circuits the composed call")
                .isTrue();
        assertThat(client.callCount())
                .as("CallNotPermitted is in Retry's ignore list → no retries, downstream never hit")
                .isZero();
    }
}
