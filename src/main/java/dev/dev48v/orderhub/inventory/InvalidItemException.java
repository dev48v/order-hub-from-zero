package dev.dev48v.orderhub.inventory;

// Day 15 — a NON-transient failure from the inventory call: the request itself is bad (e.g. a blank
// item name), the downstream equivalent of a 4xx. WHY it earns its own type: the resilience patterns
// treat it the OPPOSITE way to InventoryUnavailableException (a transient 5xx-like fault):
//   • @Retry must NOT retry it — retrying a request that's malformed will just fail again identically,
//     wasting attempts and latency. It goes in the retry's ignore-exceptions list.
//   • @CircuitBreaker must NOT count it as a downstream failure — it's the caller's fault, not the
//     dependency's, so it mustn't push the breaker toward OPEN. It goes in the breaker's
//     ignore-exceptions list.
// Distinguishing "the dependency is sick" from "you asked for something invalid" is the whole reason
// retryExceptions / ignoreExceptions exist: resilience should react to transient faults, never mask
// deterministic bugs.
public class InvalidItemException extends RuntimeException {
    public InvalidItemException(String message) {
        super(message);
    }
}
