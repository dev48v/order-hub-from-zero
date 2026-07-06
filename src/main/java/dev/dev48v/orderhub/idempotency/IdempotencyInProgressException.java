package dev.dev48v.orderhub.idempotency;

// Day 16 — the in-flight race: a second request arrived with an idempotency key that another request
// is STILL processing. WHY 409 Conflict (mapped in ApiExceptionHandler): we can't yet replay a result
// (there isn't one), and we must NOT let this second request run the handler too — that's exactly the
// double-create we're preventing. So we reject it with "already being processed; retry shortly". The
// caller's own retry will either see the now-COMPLETED result (and get it replayed) or, if the first
// request failed and released the key, win the reservation itself.
public class IdempotencyInProgressException extends RuntimeException {
    public IdempotencyInProgressException(String key) {
        super("A request with Idempotency-Key '" + key + "' is already being processed. Retry shortly.");
    }
}
