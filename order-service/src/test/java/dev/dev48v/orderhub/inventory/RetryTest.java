package dev.dev48v.orderhub.inventory;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Day 15 — proves @Retry does what we claim: retries TRANSIENT faults up to maxAttempts, recovers
// when a later attempt succeeds, and does NOT retry a non-transient (4xx-like) fault.
// WHY a plain unit test (no @SpringBootTest / Docker) — same rationale as CircuitBreakerTest: the
// behaviour under test is Resilience4j's Retry primitive, not the Spring wiring. We build a Retry with
// the same shape as application.yml's "inventory" instance (just a tiny wait so the suite stays fast)
// and drive the real InventoryClient stub through it, so it runs in surefire on every `mvn test`.
@DisplayName("Resilience4j retry: transient faults are retried, then fall back")
class RetryTest {

    private InventoryClient client;
    private Retry retry;

    @BeforeEach
    void setUp() {
        client = new InventoryClient();

        RetryConfig config = RetryConfig.custom()
                .maxAttempts(3)                          // original call + up to 2 retries
                .waitDuration(Duration.ofMillis(10))     // tiny in tests so we don't actually wait
                .retryExceptions(InventoryUnavailableException.class)   // transient → retried
                .ignoreExceptions(InvalidItemException.class)           // 4xx-like → never retried
                .build();

        retry = RetryRegistry.of(config).retry("inventory");
    }

    // Decorate exactly as @Retry(fallbackMethod = ...) does: run the guarded call through the retry,
    // and on the final failure return the degraded fallback instead of letting the exception escape.
    private InventoryStatus call(String item) {
        Supplier<InventoryStatus> guarded = Retry.decorateSupplier(retry, () -> client.checkStock(item));
        try {
            return guarded.get();
        } catch (Exception ex) {
            return InventoryStatus.degraded(item);
        }
    }

    @Test
    @DisplayName("A fully-broken downstream is retried maxAttempts times, then falls back")
    void retriesThenFallsBack() {
        client.setFailureRate(1.0); // every real call throws InventoryUnavailableException

        InventoryStatus status = call("Keyboard");

        assertThat(status.degraded()).isTrue();
        assertThat(status.source()).isEqualTo("fallback");
        assertThat(client.callCount())
                .as("maxAttempts=3 → the original call + 2 retries all hit the downstream")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("A transient blip that clears: a later attempt succeeds and no fallback is needed")
    void succeedsOnALaterAttempt() {
        AtomicInteger attempts = new AtomicInteger();
        Supplier<InventoryStatus> flaky = Retry.decorateSupplier(retry, () -> {
            if (attempts.incrementAndGet() < 3) {           // fail the first two attempts...
                throw new InventoryUnavailableException("transient blip");
            }
            return InventoryStatus.live("Keyboard", true);  // ...succeed on the third
        });

        InventoryStatus status = flaky.get();

        assertThat(status.degraded()).isFalse();
        assertThat(status.source()).isEqualTo("inventory-service");
        assertThat(attempts.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("A non-transient (4xx-like) fault is NOT retried — it's tried exactly once")
    void doesNotRetryNonTransientFaults() {
        AtomicInteger attempts = new AtomicInteger();
        Supplier<InventoryStatus> bad = Retry.decorateSupplier(retry, () -> {
            attempts.incrementAndGet();
            throw new InvalidItemException("item name must not be blank");
        });

        assertThatThrownBy(bad::get).isInstanceOf(InvalidItemException.class);
        assertThat(attempts.get())
                .as("InvalidItemException is in ignoreExceptions → tried once, never retried")
                .isEqualTo(1);
    }
}
