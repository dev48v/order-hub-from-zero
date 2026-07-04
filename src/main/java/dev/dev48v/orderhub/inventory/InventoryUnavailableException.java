package dev.dev48v.orderhub.inventory;

// Day 14 — the failure the (stubbed) inventory downstream throws when a call goes wrong.
// WHY a dedicated runtime exception: it's the concrete thing the circuit breaker COUNTS as a failure
// in its sliding window. Making it its own type (rather than a generic RuntimeException) means the
// breaker's recordExceptions/ignoreExceptions config can name it precisely, and logs/tests can assert
// on it. Unchecked so it propagates cleanly out of the guarded call into Resilience4j's advice, which
// records the failure and then routes to the fallback.
public class InventoryUnavailableException extends RuntimeException {
    public InventoryUnavailableException(String message) {
        super(message);
    }
}
