package dev.dev48v.orderhub.ratelimit;

import io.github.bucket4j.Bucket;

// Day 13 — the one thing the rate-limit filter needs: "give me the token bucket for THIS client".
// WHY an interface: the filter shouldn't care WHERE the bucket lives. There are two honest answers —
//   • DistributedBucketProvider — the bucket state lives in Redis, shared by every app instance, so
//     the limit is enforced globally across the whole cluster (the production-correct choice).
//   • LocalBucketProvider      — the bucket lives in this JVM's heap; correct for a single instance,
//     and the safe fallback when Redis isn't wired (dev/tests without a Redis).
// Both return a fully-configured Bucket keyed by the client id; the filter just calls tryConsume(1).
public interface BucketProvider {

    // Resolve (create-on-first-use) the token bucket for a client key — an API key or client IP.
    Bucket resolveBucket(String clientKey);

    // For logging/observability and the learning page: which backing store is in force.
    String backend();
}
