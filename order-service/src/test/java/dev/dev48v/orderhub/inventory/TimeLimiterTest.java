package dev.dev48v.orderhub.inventory;

import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

// Day 15 — proves @TimeLimiter turns a call that OVERRUNS its deadline into a TimeoutException that
// diverts to the fallback, while a fast call passes straight through. WHY a plain unit test on the
// TimeLimiter primitive (no Spring / Docker): the behaviour under test is the deadline itself. We give
// it the same shape as application.yml's "inventory" instance and drive the real InventoryClient stub
// with its latency dial cranked ABOVE the timeout.
@DisplayName("Resilience4j time limiter: an overrunning call is cut off and falls back")
class TimeLimiterTest {

    private InventoryClient client;
    private TimeLimiter timeLimiter;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        client = new InventoryClient();
        executor = Executors.newFixedThreadPool(2);

        timeLimiter = TimeLimiter.of(TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(200))  // hard deadline
                .cancelRunningFuture(true)
                .build());
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    // Decorate exactly as @TimeLimiter + fallback do: run the async call under the deadline; if it
    // overruns (TimeoutException) return the degraded fallback instead of blocking forever.
    private InventoryStatus call(String item) {
        Supplier<Future<InventoryStatus>> futureSupplier =
                () -> executor.submit(() -> client.checkStock(item));
        Callable<InventoryStatus> restricted =
                TimeLimiter.decorateFutureSupplier(timeLimiter, futureSupplier);
        try {
            return restricted.call();
        } catch (Exception ex) {
            return InventoryStatus.degraded(item);
        }
    }

    @Test
    @DisplayName("A call slower than the deadline is abandoned and falls back (200 degraded, not a hang)")
    void slowCallTimesOutAndFallsBack() {
        client.setLatencyMillis(500); // downstream takes 500ms, deadline is 200ms

        InventoryStatus status = call("Keyboard");

        assertThat(status.degraded()).isTrue();
        assertThat(status.source()).isEqualTo("fallback");
    }

    @Test
    @DisplayName("A call within the deadline returns the live answer untouched")
    void fastCallCompletesWithinTheDeadline() {
        client.setLatencyMillis(0); // instant

        InventoryStatus status = call("Keyboard");

        assertThat(status.degraded()).isFalse();
        assertThat(status.source()).isEqualTo("inventory-service");
    }
}
