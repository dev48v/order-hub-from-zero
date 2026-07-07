package dev.dev48v.orderhub.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dev48v.orderhub.config.IdempotencyProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

// Day 16 — the reusable idempotency behaviour, woven AROUND any @Idempotent controller method.
// WHY an @Around aspect (rather than a servlet filter like the rate limiter): the whole point is to
// capture and later REPLAY the handler's RESULT — its status, body and Location header. An aspect
// sits at exactly the right seam: it can read the request header, decide whether to run the real
// method at all, capture the ResponseEntity the method returns, and on a repeat hand back a rebuilt
// ResponseEntity without ever invoking the handler again. spring-boot-starter-aop (already on the
// classpath since Day 14) auto-enables the AspectJ proxying that makes this work.
//
// The protocol for one request carrying an Idempotency-Key:
//   1. reserve()  — an ATOMIC set-if-absent in Redis. Exactly one racing request wins.
//   2a. won      → run the handler, store the COMPLETED result at the full ttl, return it as-is.
//   2b. lost + COMPLETED already there → REPLAY the stored status/body/location (same 2xx, no
//                 duplicate created). If the payload differs from the first → 422 (key reuse).
//   2c. lost + still IN_PROGRESS       → 409 (the in-flight race).
//   3. handler threw → release() the reservation so a genuine retry can proceed.
// A request with NO key is passed straight through: the annotation enables idempotency, the client
// opts in by sending a key.
@Aspect
@Component
public class IdempotencyAspect {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyAspect.class);

    // A response header we add on a replay so clients/observability can see a repeat was de-duplicated
    // rather than freshly processed (mirrors Stripe's "Idempotent-Replayed").
    private static final String REPLAYED_HEADER = "Idempotency-Replayed";

    private final IdempotencyStore store;
    private final IdempotencyProperties props;
    private final ObjectMapper objectMapper;

    public IdempotencyAspect(IdempotencyStore store, IdempotencyProperties props, ObjectMapper objectMapper) {
        this.store = store;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(idempotent)")
    public Object apply(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
        // Master switch off → behave as if the annotation weren't there.
        if (!props.enabled()) {
            return pjp.proceed();
        }

        HttpServletRequest request = currentRequest();
        String key = request == null ? null : request.getHeader(props.headerName());

        // No key supplied → the client hasn't opted into idempotency for this call; run normally.
        if (!StringUtils.hasText(key)) {
            return pjp.proceed();
        }
        key = key.trim();

        // Fingerprint the request payload so we can later detect the same key being reused for a
        // DIFFERENT request (a client bug we reject rather than mis-serve).
        String requestHash = hashArgs(pjp.getArgs());

        // The atomic gate: only one racing request gets the reservation.
        boolean reserved = store.reserve(key, requestHash);
        if (!reserved) {
            return handleRepeat(key, requestHash);
        }

        // We own the key — process exactly once, then remember the result for future repeats.
        try {
            Object result = pjp.proceed();
            store.complete(key, capture(requestHash, result));
            return result;
        } catch (Throwable ex) {
            // The handler failed and produced no replayable result; free the key so a real retry
            // isn't blocked by a stale reservation.
            store.release(key);
            throw ex;
        }
    }

    // A repeat request that didn't win the reservation: either replay the finished result, reject a
    // key reused for a different payload, or 409 if the original is still in flight.
    private Object handleRepeat(String key, String requestHash) throws Exception {
        StoredResponse existing = store.find(key);
        if (existing != null && existing.state() == StoredResponse.State.COMPLETED) {
            if (requestHash != null && !requestHash.equals(existing.requestHash())) {
                throw new IdempotencyKeyReuseException(key);
            }
            log.debug("Replaying stored response for Idempotency-Key '{}'", key);
            return replay(existing);
        }
        // IN_PROGRESS, or the entry vanished in a rare race window — either way another attempt is
        // (or just was) running; refuse the duplicate and let the client retry.
        throw new IdempotencyInProgressException(key);
    }

    // Rebuild a ResponseEntity from the stored envelope. The body is re-parsed as a JsonNode and
    // handed back so Spring's Jackson converter re-serializes it to BYTE-FOR-BYTE the same JSON the
    // first request returned; status, Location and a replay marker header are restored too.
    private ResponseEntity<Object> replay(StoredResponse stored) throws Exception {
        Object body = stored.body() == null ? null : objectMapper.readTree(stored.body());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(stored.status())
                .header(REPLAYED_HEADER, "true");
        if (stored.location() != null) {
            builder.location(URI.create(stored.location()));
        }
        return builder.body(body);
    }

    // Turn the handler's return value into a storable envelope: pull status + Location + body out of
    // a ResponseEntity (the create endpoint returns 201 + Location), or fall back to a plain 200 with
    // the object as the body for handlers that return a bare value.
    private StoredResponse capture(String requestHash, Object result) throws Exception {
        int status = 200;
        String location = null;
        Object body = result;
        if (result instanceof ResponseEntity<?> re) {
            status = re.getStatusCode().value();
            body = re.getBody();
            URI loc = re.getHeaders().getLocation();
            if (loc != null) {
                location = loc.toString();
            }
        }
        String bodyJson = body == null ? null : objectMapper.writeValueAsString(body);
        return StoredResponse.completed(requestHash, status, bodyJson, location);
    }

    // SHA-256 over the JSON-serialized method arguments (the request body). A hash — not the raw
    // payload — keeps the stored envelope small and avoids putting request data in Redis in the clear.
    private String hashArgs(Object[] args) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(args);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            // If we can't fingerprint the payload we simply skip the different-body check; the core
            // de-dup (reserve/replay) still works. Never fail the request over the hash.
            log.warn("Could not fingerprint request for idempotency: {}", ex.getMessage());
            return null;
        }
    }

    // The current HTTP request, so we can read the Idempotency-Key header. Null outside a servlet
    // request (shouldn't happen for a controller method, but guarded so the aspect degrades safely).
    private HttpServletRequest currentRequest() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        return attrs instanceof ServletRequestAttributes sra ? sra.getRequest() : null;
    }
}
