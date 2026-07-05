package dev.dev48v.orderhub.inventory;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

// Day 14 → 15 — a STUB of a downstream "inventory service" that the order flow calls to check stock.
// In the real system (Phase 3) this becomes an HTTP/Feign call to a separate Inventory microservice;
// today it's an in-process stand-in whose only job is to be realistically FLAKY (fails a fraction of
// calls) and SLOW (optional latency) so the resilience patterns have something to protect us from.
//
// Day 14 added the CIRCUIT BREAKER around the synchronous checkStock(). Day 15 adds the rest of the
// fault-tolerance toolkit and COMPOSES it on a second, asynchronous entry point, checkStockResilient():
//   • @Retry       — a few quick attempts (exponential backoff + jitter) for TRANSIENT blips.
//   • @TimeLimiter — a hard deadline so a hanging call is turned into a TimeoutException, not a stuck
//                    thread (needs a CompletableFuture-returning method).
//   • @Bulkhead    — a semaphore that caps CONCURRENT in-flight calls so a slow dependency can't
//                    swallow the whole thread pool.
//   • @CircuitBreaker — the Day-14 breaker, now also guarding the composed path.
//
// COMPOSITION / ASPECT ORDER (the crux of the day). Resilience4j applies its aspects in a fixed,
// documented order — from OUTER to INNER:
//     Retry ( CircuitBreaker ( RateLimiter ( TimeLimiter ( Bulkhead ( call ) ) ) ) )
// We use Retry, CircuitBreaker, TimeLimiter and Bulkhead, so effectively:
//     Retry ( CircuitBreaker ( TimeLimiter ( Bulkhead ( call ) ) ) )
// WHY this order is the right default, and the tradeoff:
//   1. Retry is OUTERMOST — a retry re-enters the whole stack, so each attempt is a fresh pass through
//      the breaker. That means (a) once the breaker is OPEN, a retry attempt fails FAST with
//      CallNotPermittedException instead of hammering the dependency, and (b) every attempt is recorded
//      in the breaker's window. If Retry were INSIDE the breaker, all N attempts would look like ONE
//      breaker call and retries could mask a failing dependency from the breaker.
//   2. CircuitBreaker sits OUTSIDE TimeLimiter — so a TimeoutException counts as a breaker FAILURE.
//      A dependency that's merely slow (never erroring) will still trip the breaker. The alternative
//      order (TimeLimiter outside CircuitBreaker) would time-limit the fast fail-open path too and
//      would NOT feed timeouts into the failure window — worse for a "slow, not dead" dependency, so
//      we prefer the default.
//   3. Bulkhead is INNERMOST — its permit is held for exactly the real in-flight downstream call, so
//      maxConcurrentCalls maps 1:1 to concurrent work actually hitting the dependency.
// The order is Resilience4j's default; it can be retuned with resilience4j.<pattern>.<pattern>AspectOrder
// (higher value = more outer) if a service needs a different composition.
//
// FALLBACK placement: the fallbackMethod lives on the OUTERMOST aspect (@Retry). That way retries run
// FIRST — every attempt fails through breaker/timeout/bulkhead — and only once they're EXHAUSTED does
// the fallback fire. If the fallback were on the inner @CircuitBreaker it would recover the exception
// before @Retry ever saw it, so nothing would retry.
@Component
public class InventoryClient {

    private static final Logger log = LoggerFactory.getLogger(InventoryClient.class);

    // Togglable failure rate in [0.0, 1.0]. 0.0 = always succeeds (healthy), 1.0 = always fails
    // (downstream completely down). Volatile so a change from setFailureRate() is seen immediately by
    // the request threads. Lets a test — or the demo endpoint — flip the downstream between healthy
    // and broken on the fly and watch the patterns react.
    private volatile double failureRate = 0.0;

    // Optional artificial latency (ms). Day 15 pairs a @TimeLimiter with this to show a slow call
    // being cut off: dial latency ABOVE the timeout and the call is turned into a TimeoutException →
    // fallback, instead of a thread stuck waiting.
    private volatile long latencyMillis = 0L;

    // Counts how many real calls actually reached the (stubbed) downstream — as opposed to being
    // short-circuited by an OPEN breaker or rejected by a full bulkhead (those never enter doCheckStock).
    // A retry that re-invokes the call bumps this again, which is exactly how a test proves retries fired.
    private final AtomicInteger callCount = new AtomicInteger();

    // Day 14: the synchronous, circuit-breaker-guarded read. Unchanged — the order flow's simple stock
    // check. Wraps the shared doCheckStock() body in the "inventory" breaker + a degraded fallback.
    @CircuitBreaker(name = "inventory", fallbackMethod = "checkStockFallback")
    public InventoryStatus checkStock(String item) {
        return doCheckStock(item);
    }

    // Day 15: the fully RESILIENT read — the same downstream call, now wrapped in the whole stack.
    // It returns a CompletableFuture because @TimeLimiter can only put a deadline on an asynchronous
    // call: the future runs the work on another thread, and the TimeLimiter completes it exceptionally
    // if it overruns. Resilience4j weaves the aspects around this method in the order documented above.
    // On exhausted retries / timeout / open breaker / full bulkhead, checkStockResilientFallback() runs.
    @Retry(name = "inventory", fallbackMethod = "checkStockResilientFallback")
    @TimeLimiter(name = "inventory")
    public CompletableFuture<InventoryStatus> checkStockResilient(String item) {
        // supplyAsync runs doCheckStock() on a worker thread; the returned future completes only when
        // that work finishes, so the bulkhead permit (acquired by the inner aspect) is held for the
        // real duration of the call, and the TimeLimiter can abandon it if it runs long.
        return CompletableFuture.supplyAsync(() -> doCheckStock(item));
    }

    // The shared body of the downstream call — the actual (stubbed) work both entry points run.
    // Deliberately NOT annotated: it's plain code that the annotated public methods call, so the
    // resilience aspects wrap the PUBLIC method once, not this helper.
    private InventoryStatus doCheckStock(String item) {
        // A malformed request is a NON-transient (4xx-like) fault: reject it up front. Retry ignores
        // this (retrying won't help) and the breaker ignores it (it's the caller's fault, not the
        // dependency's) — see InvalidItemException.
        if (item == null || item.isBlank()) {
            throw new InvalidItemException("item name must not be blank");
        }

        callCount.incrementAndGet();

        // Model a slow dependency if latency was dialled up.
        if (latencyMillis > 0) {
            sleep(latencyMillis);
        }

        // Model an unreliable dependency: fail a `failureRate` fraction of calls by throwing, the way
        // a remote call would surface a 5xx / connection reset. This TRANSIENT exception is what the
        // breaker records as a failure and what @Retry is configured to retry.
        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            throw new InventoryUnavailableException(
                    "inventory-service call failed for item '" + item + "'");
        }

        // Happy path: an authoritative, live answer from the (stubbed) downstream.
        return InventoryStatus.live(item, true);
    }

    // Day 14 fallback for the synchronous path: same args + Throwable, same return type. Returns a
    // graceful DEGRADED status (a 200 upstream, never a 500) so an order can still be placed while the
    // inventory service is unhealthy. Cheap and never throws — a fallback that can fail defeats the purpose.
    InventoryStatus checkStockFallback(String item, Throwable cause) {
        log.warn("Inventory check for '{}' degraded to fallback: {}", item, cause.toString());
        return InventoryStatus.degraded(item);
    }

    // Day 15 fallback for the RESILIENT path. Same signature as the guarded method (args + trailing
    // Throwable) but it must return the SAME type — a CompletableFuture — so we hand back an
    // already-completed future carrying the degraded status. Resilience4j routes here after retries are
    // exhausted, on a timeout, on an OPEN breaker, or when the bulkhead is full — any way the call
    // couldn't be served, the caller still gets a graceful answer.
    CompletableFuture<InventoryStatus> checkStockResilientFallback(String item, Throwable cause) {
        log.warn("Resilient inventory check for '{}' degraded to fallback: {}", item, cause.toString());
        return CompletableFuture.completedFuture(InventoryStatus.degraded(item));
    }

    // --- test / demo hooks: flip the downstream between healthy and broken at runtime ---

    public void setFailureRate(double failureRate) {
        this.failureRate = Math.max(0.0, Math.min(1.0, failureRate));
    }

    public double getFailureRate() {
        return failureRate;
    }

    public void setLatencyMillis(long latencyMillis) {
        this.latencyMillis = Math.max(0L, latencyMillis);
    }

    public long getLatencyMillis() {
        return latencyMillis;
    }

    public int callCount() {
        return callCount.get();
    }

    public void resetCallCount() {
        callCount.set(0);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new InventoryUnavailableException("inventory-service call interrupted");
        }
    }
}
