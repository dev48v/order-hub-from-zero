package dev.dev48v.orderhub.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.Map;

// Day 11 — Redis caching wiring.
// WHY: reading the same order over and over hammers PostgreSQL for a row that rarely changes.
// Spring's caching abstraction lets us keep that answer in Redis — an in-memory key/value store —
// so a repeat read is a ~1ms memory lookup instead of a database round-trip. We turn the whole
// feature on with ONE annotation (@EnableCaching) and then only touch the service methods with
// @Cacheable/@CacheEvict; this class just tells Spring HOW to store the values.
//
// The cache-aside pattern (what @Cacheable implements): on a read, look in Redis first; on a HIT
// return it; on a MISS run the method, then store the result before returning. Writes EVICT the
// stale entries so the next read repopulates from the database.
@Configuration
@EnableCaching
public class CacheConfig {

    // Named cache regions. Keeping them as constants avoids typo'd cache names drifting between
    // the config and the annotations on OrderService.
    public static final String ORDER_CACHE = "order";   // one entry per order id
    public static final String ORDERS_CACHE = "orders"; // the full list, under a single key

    // The value serializer. Redis stores bytes, so every cached object must be turned into bytes
    // and back. GenericJackson2JsonRedisSerializer writes JSON and — crucially — embeds the Java
    // type (an "@class" field) so the value can be read back as the right type later. We hand it a
    // customised ObjectMapper: JavaTimeModule so Instant serializes as ISO-8601, ParameterNamesModule
    // so Jackson can call the immutable Order's @JsonCreator constructor by parameter name, and
    // field visibility so it can read the private final fields directly.
    @Bean
    public GenericJackson2JsonRedisSerializer redisValueSerializer() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .registerModule(new ParameterNamesModule());
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        return new GenericJackson2JsonRedisSerializer(mapper);
    }

    // The RedisCacheManager: the bean Spring's @Cacheable/@CacheEvict route through. It is backed
    // by the injected RedisConnectionFactory, which Spring Boot auto-configures from
    // spring.data.redis.host / .port (see application-dev.yml / application-prod.yml) using the
    // Lettuce client. We give it a sensible default TTL and a longer, per-cache TTL for "orders".
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                          GenericJackson2JsonRedisSerializer valueSerializer) {

        // Default policy for every cache region unless overridden below.
        //  - entryTtl(10m): entries auto-expire after 10 minutes. A TTL is a safety net — even if an
        //    eviction were ever missed, a stale value can only live for at most this long. It also
        //    bounds memory: dead keys clean themselves up.
        //  - disableCachingNullValues(): never cache a null. Caching "not found" here would let a
        //    missing-then-created order stay invisible until the TTL lapsed.
        //  - keys as plain strings (readable in redis-cli), values as our JSON serializer above.
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(valueSerializer));

        // Per-cache override: the list cache ("orders") is a single hot key that we EVICT on every
        // write anyway, so a slightly longer TTL (30m) is safe and squeezes more hits out of it
        // during read-heavy stretches. Individual orders keep the 10-minute default.
        Map<String, RedisCacheConfiguration> perCache = Map.of(
                ORDERS_CACHE, defaults.entryTtl(Duration.ofMinutes(30))
        );

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(perCache)
                .build();
    }
}
