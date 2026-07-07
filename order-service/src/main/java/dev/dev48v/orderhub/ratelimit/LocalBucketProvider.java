package dev.dev48v.orderhub.ratelimit;

import dev.dev48v.orderhub.config.RateLimitProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

// Day 13 — the PER-INSTANCE (in-JVM) token-bucket provider.
// WHY it exists: it's the honest fallback when there's no Redis wired (local dev, a plain unit run).
// Each client gets one Bucket living in this JVM's heap, cached in a ConcurrentHashMap so repeat
// requests from the same client reuse the same bucket (otherwise every request would get a fresh,
// full bucket and the limit would never bite).
//
// THE CAVEAT (documented on purpose): this only limits traffic hitting THIS instance. Run three
// replicas behind a load balancer and each keeps its own count — a client effectively gets 3× the
// limit. That's exactly why production uses DistributedBucketProvider (Redis-backed), where the
// bucket state is shared so the limit is one global number across all nodes. The algorithm below is
// identical; only the STORAGE differs.
public class LocalBucketProvider implements BucketProvider {

    private final RateLimitProperties props;

    // One bucket per client key, created on first sight and reused thereafter. computeIfAbsent is
    // atomic, so two concurrent first-requests from the same client still share a single bucket.
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public LocalBucketProvider(RateLimitProperties props) {
        this.props = props;
    }

    @Override
    public Bucket resolveBucket(String clientKey) {
        return buckets.computeIfAbsent(clientKey, key -> newBucket());
    }

    // Build a bucket that holds `capacity` tokens and refills them GREEDILY over refillPeriod.
    // Greedy refill drips tokens back continuously (≈capacity/period per unit time) rather than
    // dumping the whole allowance at the end of the window — so a client recovers smoothly instead
    // of in one lump, which is friendlier and avoids synchronized bursts at each window boundary.
    private Bucket newBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(props.capacity())
                .refillGreedy(props.capacity(), props.refillPeriod())
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    @Override
    public String backend() {
        return "in-memory (per-instance)";
    }
}
