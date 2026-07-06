package dev.dev48v.orderhub.idempotency;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

// Day 16 — the envelope we keep in Redis under each idempotency key.
// WHY store this shape (and not just the response body): safe retries need TWO things beyond the
// body — a STATE, so a concurrent second request can tell "someone is already processing this" from
// "it's done, here's the answer"; and a REQUEST FINGERPRINT, so we can catch a client reusing one
// key for a DIFFERENT request (a bug we should reject, not silently serve the wrong cached answer).
//
// The lifecycle of one key:
//   1. First request wins the reservation → we store an IN_PROGRESS envelope (body/status empty).
//   2. When the handler returns → we OVERWRITE it with a COMPLETED envelope carrying the real
//      status, the serialized body and any Location header, at the full replay ttl.
//   3. Any repeat of the SAME request → we find COMPLETED and replay status+body+location verbatim.
//   4. A repeat that arrives while still IN_PROGRESS → 409 Conflict (the in-flight race).
//
// It's a record with an explicit @JsonCreator so Jackson round-trips it through the Redis string
// value cleanly (the Instant needs the JavaTimeModule the app's ObjectMapper already registers).
public record StoredResponse(
        @JsonProperty("state") State state,
        @JsonProperty("requestHash") String requestHash,
        @JsonProperty("status") int status,
        @JsonProperty("body") String body,       // the response body, already serialized to JSON
        @JsonProperty("location") String location, // the Location header value, if any (null otherwise)
        @JsonProperty("storedAt") Instant storedAt
) {
    // IN_PROGRESS = a request holding the reservation is still running; COMPLETED = the result is
    // final and replayable. Two states are all the protocol needs.
    public enum State { IN_PROGRESS, COMPLETED }

    @JsonCreator
    public StoredResponse {
    }

    // The reservation marker written the instant a request claims a key — before its handler runs.
    static StoredResponse inProgress(String requestHash) {
        return new StoredResponse(State.IN_PROGRESS, requestHash, 0, null, null, Instant.now());
    }

    // The final, replayable result, written once the handler has produced its response.
    static StoredResponse completed(String requestHash, int status, String body, String location) {
        return new StoredResponse(State.COMPLETED, requestHash, status, body, location, Instant.now());
    }
}
