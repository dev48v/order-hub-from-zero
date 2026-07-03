package dev.dev48v.orderhub.ratelimit;

import dev.dev48v.orderhub.config.RateLimitProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.distributed.proxy.ProxyManager;

import java.util.function.Supplier;

// Day 13 — the PRODUCTION token-bucket provider: bucket state lives in REDIS, shared by every node.
// WHY this is the correct one for a multi-instance deployment: the bucket's tokens are stored under
// a Redis key (one per client), and Bucket4j mutates them with an atomic compare-and-swap Lua script.
// So whether a client's request lands on replica A, B or C, they all decrement the SAME counter —
// the limit is one global "20/min per client", not "20/min per client PER NODE". This is the single
// reason to pay for Redis here instead of the in-JVM LocalBucketProvider: correctness under scale-out.
//
// The heavy lifting (opening the Lettuce connection, building the LettuceBasedProxyManager) is done
// once in RateLimitConfig; this class just holds that ProxyManager and, per client, hands back a
// Bucket proxy bound to that client's Redis key. proxyManager.getProxy(key, cfg) is lazy: the bucket
// is created in Redis on first use and reused (by key) forever after.
public class DistributedBucketProvider implements BucketProvider {

    // The Bucket4j proxy manager over Redis. Parameterised by the key type Bucket4j stores under —
    // here byte[] keys, matching the byte[]-codec Lettuce connection built in RateLimitConfig.
    private final ProxyManager<byte[]> proxyManager;
    private final RateLimitProperties props;

    public DistributedBucketProvider(ProxyManager<byte[]> proxyManager, RateLimitProperties props) {
        this.proxyManager = proxyManager;
        this.props = props;
    }

    @Override
    public Bucket resolveBucket(String clientKey) {
        // Prefix the key so our rate-limit entries are easy to spot in redis-cli and never collide
        // with the Day 11 cache keys. UTF-8 bytes because this ProxyManager is keyed by byte[].
        byte[] redisKey = ("ratelimit:" + clientKey).getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // The configuration is supplied lazily: it's only read when the bucket doesn't yet exist in
        // Redis. Existing buckets keep their live token count across requests (and across nodes).
        return proxyManager.getProxy(redisKey, bucketConfiguration());
    }

    // Same token-bucket shape as the local provider (capacity + greedy refill over the period) — only
    // the STORAGE differs. Kept as a Supplier because getProxy wants a lazy config factory.
    private Supplier<BucketConfiguration> bucketConfiguration() {
        return () -> {
            Bandwidth limit = Bandwidth.builder()
                    .capacity(props.capacity())
                    .refillGreedy(props.capacity(), props.refillPeriod())
                    .build();
            return BucketConfiguration.builder().addLimit(limit).build();
        };
    }

    @Override
    public String backend() {
        return "redis (distributed)";
    }
}
