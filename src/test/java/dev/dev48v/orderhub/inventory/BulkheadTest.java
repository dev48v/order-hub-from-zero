package dev.dev48v.orderhub.inventory;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

// Day 15 — proves the semaphore @Bulkhead caps CONCURRENCY: with N permits, no more than N calls run
// at once and the overflow is rejected immediately with BulkheadFullException (→ fallback). WHY a plain
// unit test on the Bulkhead primitive (no Spring / Docker): the behaviour under test is the semaphore.
// We fire more concurrent calls than there are permits, hold each one (the stub's latency) so they
// overlap, and use a CyclicBarrier so they all start together — making the rejection deterministic.
@DisplayName("Resilience4j bulkhead: over-limit concurrent calls are rejected to the fallback")
class BulkheadTest {

    private static final int MAX_CONCURRENT = 2;
    private static final int CALLERS = 6;

    private InventoryClient client;
    private Bulkhead bulkhead;
    private ExecutorService pool;

    @BeforeEach
    void setUp() {
        client = new InventoryClient();
        client.setLatencyMillis(500); // hold each permit long enough that all callers overlap
        pool = Executors.newFixedThreadPool(CALLERS);

        bulkhead = Bulkhead.of("inventory", BulkheadConfig.custom()
                .maxConcurrentCalls(MAX_CONCURRENT)
                .maxWaitDuration(Duration.ZERO)   // fail fast when full, don't queue
                .build());
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    @DisplayName("Only maxConcurrentCalls run at once; the rest get BulkheadFullException → degraded")
    void rejectsOverLimitConcurrentCalls() throws Exception {
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger concurrentNow = new AtomicInteger();
        AtomicInteger peakConcurrent = new AtomicInteger();

        // Instrument the actual work so we can observe the real concurrency the bulkhead allowed.
        Supplier<InventoryStatus> work = () -> {
            peakConcurrent.accumulateAndGet(concurrentNow.incrementAndGet(), Math::max);
            try {
                return client.checkStock("Keyboard");
            } finally {
                concurrentNow.decrementAndGet();
            }
        };
        Supplier<InventoryStatus> guarded = Bulkhead.decorateSupplier(bulkhead, work);

        // A barrier so all CALLERS attempt to enter the bulkhead at essentially the same instant.
        CyclicBarrier barrier = new CyclicBarrier(CALLERS);
        List<Future<InventoryStatus>> futures = new ArrayList<>();
        for (int i = 0; i < CALLERS; i++) {
            futures.add(pool.submit(() -> {
                barrier.await();
                try {
                    InventoryStatus s = guarded.get();
                    admitted.incrementAndGet();
                    return s;
                } catch (BulkheadFullException full) {
                    // Exactly what @Bulkhead(fallbackMethod=...) does: rejected → graceful degraded.
                    rejected.incrementAndGet();
                    return InventoryStatus.degraded("Keyboard");
                }
            }));
        }
        for (Future<InventoryStatus> f : futures) {
            f.get();
        }

        assertThat(admitted.get())
                .as("the semaphore admits exactly maxConcurrentCalls")
                .isEqualTo(MAX_CONCURRENT);
        assertThat(rejected.get())
                .as("every caller over the limit is rejected to the fallback")
                .isEqualTo(CALLERS - MAX_CONCURRENT);
        assertThat(peakConcurrent.get())
                .as("the bulkhead never lets more than maxConcurrentCalls run at once")
                .isLessThanOrEqualTo(MAX_CONCURRENT);
    }
}
