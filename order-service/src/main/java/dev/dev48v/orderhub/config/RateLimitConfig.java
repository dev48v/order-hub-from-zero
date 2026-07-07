package dev.dev48v.orderhub.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dev48v.orderhub.ratelimit.BucketProvider;
import dev.dev48v.orderhub.ratelimit.DistributedBucketProvider;
import dev.dev48v.orderhub.ratelimit.LocalBucketProvider;
import dev.dev48v.orderhub.ratelimit.RateLimitFilter;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.time.Duration;

// Day 13 — assembles the rate-limiting backend and exposes ONE BucketProvider bean for the filter.
// WHY the split into two beans: we PREFER the real, Redis-backed distributed limiter (the Redis from
// Day 11 is already here), but the app must still start and self-limit if Redis is unreachable — so
// we try to build the distributed provider, and fall back to the in-JVM one if the connection can't
// be opened. The filter depends only on the BucketProvider interface and never knows which it got.
//
// HOW "distributed" actually works: Bucket4j's LettuceBasedProxyManager keeps each client's bucket
// state under a Redis key and updates it with an atomic compare-and-swap Lua script. Every app
// instance points at the same Redis, so they all mutate the same counter — the limit is enforced
// ONCE across the whole cluster, not once per node. Swap the ProxyManager for a Hazelcast/Ignite/
// DynamoDB one and the rest of the code is unchanged; that's the point of the ProxyManager seam.
@Configuration
public class RateLimitConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitConfig.class);

    // Same Redis coordinates Spring Boot already uses for the Day 11 cache. We open our OWN Lettuce
    // connection here (with a byte[] codec Bucket4j requires) rather than reusing Spring's cache
    // connection factory, so the two concerns stay cleanly separated.
    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    // The single BucketProvider the filter depends on. We try to build the Redis-backed
    // (distributed) provider first — the production-correct choice, since the limit is then shared
    // across every instance — and quietly fall back to per-instance in-memory buckets if Redis can't
    // be reached, so the app never fails to start just because Redis is down. One bean, one clear
    // decision made at startup; the filter is oblivious to which backend it ends up with.
    @Bean
    public BucketProvider bucketProvider(RateLimitProperties props) {
        ProxyManager<byte[]> proxyManager = tryOpenRedisProxyManager(props);
        if (proxyManager != null) {
            return new DistributedBucketProvider(proxyManager, props);
        }
        return new LocalBucketProvider(props);
    }

    // Register the rate-limit filter explicitly (rather than as a @Component). Doing it here lets us
    // pin it to the /api/* URL space and run it FIRST (HIGHEST_PRECEDENCE), so a throttled request is
    // rejected before any other filter or the dispatcher does work — and it keeps the filter out of
    // @WebMvcTest slices, which have no BucketProvider bean to build it with.
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            BucketProvider bucketProvider, RateLimitProperties props, ObjectMapper objectMapper) {
        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new RateLimitFilter(bucketProvider, props, objectMapper));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("rateLimitFilter");
        return registration;
    }

    // Attempt to open a Redis-backed LettuceBasedProxyManager against the same Redis the Day 11 cache
    // uses. Returns null (rather than throwing) if Redis is unreachable, so the caller can fall back.
    // Bucket4j requires a byte[] VALUE codec — it stores the serialized bucket as bytes — so we
    // connect with ByteArrayCodec; keys are byte[] too (see DistributedBucketProvider).
    private ProxyManager<byte[]> tryOpenRedisProxyManager(RateLimitProperties props) {
        try {
            RedisClient client = RedisClient.create(
                    RedisURI.builder().withHost(redisHost).withPort(redisPort)
                            .withTimeout(Duration.ofSeconds(2)).build());

            // A byte[]/byte[] connection. Reusing this single connection for every client bucket is
            // fine — Lettuce connections are thread-safe and multiplexed over one socket.
            StatefulRedisConnection<byte[], byte[]> connection = client.connect(ByteArrayCodec.INSTANCE);

            // Fail fast into the fallback if Redis isn't actually there: a cheap PING round-trips
            // within the 2s timeout instead of surfacing on the first throttled request.
            connection.sync().ping();

            // expirationAfterWrite: let Redis auto-delete a client's bucket key once enough idle time
            // has passed that the bucket would be back to full anyway — otherwise every one-off client
            // IP would leave a key in Redis forever. "up to max" ties the TTL to the refill time.
            ProxyManager<byte[]> manager = Bucket4jLettuce.casBasedBuilder(connection)
                    .expirationAfterWrite(ExpirationAfterWriteStrategy
                            .basedOnTimeForRefillingBucketUpToMax(props.refillPeriod().plusSeconds(5)))
                    .build();

            log.info("Rate limiting: using Redis-backed distributed buckets at {}:{}", redisHost, redisPort);
            return manager;
        } catch (Exception ex) {
            log.warn("Rate limiting: Redis unavailable at {}:{} ({}). "
                    + "Falling back to per-instance in-memory buckets.", redisHost, redisPort, ex.getMessage());
            return null;
        }
    }
}
