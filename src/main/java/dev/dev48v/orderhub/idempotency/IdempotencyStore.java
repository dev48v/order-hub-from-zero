package dev.dev48v.orderhub.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dev48v.orderhub.config.IdempotencyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

// Day 16 — the Redis home for idempotency keys. Reuses the SAME Redis that Day 11 caching and Day 13
// rate limiting already run on — one shared infrastructure dependency, three concerns. We talk to it
// through the auto-configured StringRedisTemplate (plain string keys + values, easy to read in
// redis-cli) and serialize the StoredResponse envelope to a JSON string with the app's ObjectMapper.
//
// The one operation that makes this correct under concurrency is reserve(): it is an atomic SET-IF-
// ABSENT (Redis SETNX + TTL in a single round trip). Two identical requests that arrive at the same
// instant BOTH call it, but Redis guarantees only ONE gets `true` back — that request owns the key
// and proceeds; the other gets `false` and is turned away (replayed if the first has finished, or
// 409'd while it's still in flight). No read-then-write race, even across app instances, because the
// check-and-set happens inside Redis, not in our JVM.
@Component
public class IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyStore.class);

    // Prefix so idempotency entries are obvious in redis-cli and never collide with the Day 11 cache
    // ("order::…") or Day 13 rate-limit ("ratelimit:…") keys sharing this Redis.
    private static final String PREFIX = "idempotency:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final IdempotencyProperties props;

    public IdempotencyStore(StringRedisTemplate redis, ObjectMapper objectMapper, IdempotencyProperties props) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    // Atomically claim the key for this request. Writes an IN_PROGRESS marker only if the key does
    // NOT already exist, with the short lock TTL so a crashed request can't wedge the key forever.
    // Returns true iff THIS caller won the reservation (and must therefore process the request).
    public boolean reserve(String key, String requestHash) {
        Boolean won = redis.opsForValue().setIfAbsent(
                redisKey(key), write(StoredResponse.inProgress(requestHash)), props.lockTtl());
        return Boolean.TRUE.equals(won);
    }

    // Read the current envelope for a key, or null if there is none (never reserved, or expired).
    public StoredResponse find(String key) {
        String json = redis.opsForValue().get(redisKey(key));
        return json == null ? null : read(json);
    }

    // Overwrite the reservation with the final, replayable result at the FULL ttl. A plain SET (not
    // setIfAbsent) because we already hold the reservation and are upgrading IN_PROGRESS → COMPLETED,
    // extending its life from the short lock TTL to the long replay TTL.
    public void complete(String key, StoredResponse completed) {
        redis.opsForValue().set(redisKey(key), write(completed), props.ttl());
    }

    // Drop the reservation so a legitimate retry can start fresh — used when the handler THREW, so no
    // result was produced and the key must not stay locked pointing at a request that never finished.
    public void release(String key) {
        redis.delete(redisKey(key));
    }

    private String redisKey(String key) {
        return PREFIX + key;
    }

    private String write(StoredResponse stored) {
        try {
            return objectMapper.writeValueAsString(stored);
        } catch (Exception ex) {
            // Serialization of our own small record shouldn't fail; wrap defensively so a freak
            // failure surfaces clearly rather than as a confusing Redis error.
            throw new IllegalStateException("Failed to serialize idempotency record", ex);
        }
    }

    private StoredResponse read(String json) {
        try {
            return objectMapper.readValue(json, StoredResponse.class);
        } catch (Exception ex) {
            // A corrupt/incompatible entry (e.g. left by an older version) shouldn't hard-fail the
            // request — log and treat it as absent so the request proceeds rather than 500s.
            log.warn("Ignoring unreadable idempotency entry: {}", ex.getMessage());
            return null;
        }
    }
}
