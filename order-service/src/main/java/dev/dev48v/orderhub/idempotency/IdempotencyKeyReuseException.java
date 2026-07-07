package dev.dev48v.orderhub.idempotency;

// Day 16 — a key was reused for a DIFFERENT request. WHY this is an error (422 Unprocessable Entity,
// mapped in ApiExceptionHandler) rather than a silent replay: an idempotency key means "this is the
// SAME logical operation as before". If the body differs, the client has a bug — either it recycled a
// key it shouldn't have, or it changed the request and forgot to mint a fresh key. Replaying the old
// result would hand back an answer for a request the caller didn't make; running the new one would
// break the key's promise. Refusing loudly surfaces the mistake instead of hiding it. We detect it by
// comparing a hash of the request payload against the one recorded when the key was first stored.
public class IdempotencyKeyReuseException extends RuntimeException {
    public IdempotencyKeyReuseException(String key) {
        super("Idempotency-Key '" + key + "' was already used for a request with a different payload.");
    }
}
