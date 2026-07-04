package dev.dev48v.orderhub.inventory;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

// Day 14 — a STUB of a downstream "inventory service" that the order flow calls to check stock.
// In the real system (Phase 3) this becomes an HTTP/Feign call to a separate Inventory microservice;
// today it's an in-process stand-in whose only job is to be realistically FLAKY so the circuit
// breaker has something to protect us from. It fails a configurable fraction of calls (and can also
// be slow), exactly the way a struggling remote dependency does.
//
// THE CIRCUIT BREAKER (the point of the day):
//   @CircuitBreaker(name = "inventory", fallbackMethod = "checkStockFallback")
//   • name         — ties this method to the "inventory" breaker configured under
//                    resilience4j.circuitbreaker.instances.inventory.* in application.yml
//                    (sliding window, failure-rate threshold, wait duration, half-open trial calls).
//   • fallbackMethod — when the breaker is OPEN (short-circuiting) OR the call throws, Resilience4j
//                    routes to this method INSTEAD of letting the exception escape. That's how a
//                    failing dependency degrades gracefully instead of 500-ing the whole order.
//
// How the breaker moves (all driven by the config, not by us):
//   CLOSED  → calls pass through; failures are counted in a rolling window.
//   OPEN    → once the failure RATE in that window crosses the threshold, the breaker trips: every
//             call is short-circuited straight to the fallback for `waitDurationInOpenState`, giving
//             the downstream time to recover instead of being hammered.
//   HALF_OPEN → after the wait, a few trial calls are permitted; if they succeed the breaker closes,
//             if they fail it opens again.
//
// The fallback signature MUST match the guarded method plus a trailing Throwable, and return the
// same type — that's how Resilience4j finds and binds it.
@Component
public class InventoryClient {

    private static final Logger log = LoggerFactory.getLogger(InventoryClient.class);

    // Togglable failure rate in [0.0, 1.0]. 0.0 = always succeeds (healthy), 1.0 = always fails
    // (downstream completely down). Volatile so a change from setFailureRate() is seen immediately by
    // the request threads calling checkStock(). Kept as instance state so a test — or the demo
    // endpoint — can flip the downstream between healthy and broken on the fly and watch the breaker
    // react. Defaults to 0 so normal operation is unaffected.
    private volatile double failureRate = 0.0;

    // Optional artificial latency (ms). Left at 0 today; Day 15 pairs a @TimeLimiter with this to
    // show timeouts tripping the breaker too. Kept here so the stub already models "slow", not just
    // "errors".
    private volatile long latencyMillis = 0L;

    // Simple call counter so logs/tests can see how many real calls actually reached the downstream
    // (as opposed to being short-circuited to the fallback while OPEN — those never enter this method).
    private final AtomicInteger callCount = new AtomicInteger();

    // The guarded downstream call. Resilience4j wraps it: it runs inside the "inventory" breaker,
    // which counts outcomes and, once too many fail, stops calling it and diverts to the fallback.
    @CircuitBreaker(name = "inventory", fallbackMethod = "checkStockFallback")
    public InventoryStatus checkStock(String item) {
        callCount.incrementAndGet();

        // Model a slow dependency if latency was dialled up.
        if (latencyMillis > 0) {
            sleep(latencyMillis);
        }

        // Model an unreliable dependency: fail a `failureRate` fraction of calls by throwing, the way
        // a remote call would surface a 5xx / connection reset. These thrown exceptions are what the
        // breaker records as failures in its sliding window.
        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            throw new InventoryUnavailableException(
                    "inventory-service call failed for item '" + item + "'");
        }

        // Happy path: an authoritative, live answer from the (stubbed) downstream.
        return InventoryStatus.live(item, true);
    }

    // The fallback: Resilience4j calls this — with the same args plus the Throwable that caused the
    // diversion — whenever checkStock() would have failed or the breaker is OPEN and short-circuits.
    // It returns a graceful DEGRADED status (HTTP 200 upstream, not a 500), so an order can still be
    // placed while the inventory service is unhealthy. The method is intentionally cheap and never
    // throws — a fallback that can fail defeats the purpose.
    //
    // NOTE: this must be present and match the signature, or Resilience4j throws at call time. It's
    // package-private (not public) on purpose — it's an implementation detail wired by annotation,
    // not part of the client's API — Resilience4j still finds it reflectively.
    InventoryStatus checkStockFallback(String item, Throwable cause) {
        log.warn("Inventory check for '{}' degraded to fallback: {}", item, cause.toString());
        return InventoryStatus.degraded(item);
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
