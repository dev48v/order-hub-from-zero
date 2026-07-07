package dev.dev48v.orderhub.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.dev48v.orderhub.config.RateLimitProperties;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

// Day 13 — the rate-limit gate, as a servlet filter.
// WHY a filter (not an interceptor): a filter runs at the very edge of the request, before Spring
// MVC even dispatches to a controller — so a throttled request is rejected as cheaply as possible,
// never touching the service or database. OncePerRequestFilter guarantees it runs exactly once per
// request even through forwards/includes. @Order(HIGHEST_PRECEDENCE) puts it ahead of other filters.
//
// The flow per request:
//   1. Identify the client — prefer the X-API-Key header (a real, stable identity); fall back to the
//      caller's IP. This is the bucket KEY: each distinct client gets its own token bucket.
//   2. Ask the BucketProvider for that client's bucket and tryConsumeAndReturnRemaining(1) — one call
//      that atomically takes a token if available and tells us how many remain (and, on failure, how
//      long until one refills). Atomic matters under Redis: no read-then-write race across nodes.
//   3. Allowed  → add X-RateLimit-Remaining and continue down the chain.
//      Throttled → short-circuit with 429, a Retry-After header, and an RFC-7807 problem+json body.
//
// NOT a @Component: it's registered explicitly via a FilterRegistrationBean in RateLimitConfig, which
// pins its URL mapping (/api/*) and its order (first). Registering it there rather than as a bare
// @Component also keeps it out of @WebMvcTest slices, which would otherwise try (and fail) to build it
// without the BucketProvider bean in scope.
public class RateLimitFilter extends OncePerRequestFilter {

    private final BucketProvider bucketProvider;
    private final RateLimitProperties props;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(BucketProvider bucketProvider,
                           RateLimitProperties props,
                           ObjectMapper objectMapper) {
        this.bucketProvider = bucketProvider;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    // Only guard the API surface. Swagger UI, the OpenAPI JSON, error dispatches and static assets
    // are none of the rate limiter's business, and limiting them would break the docs UI.
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!props.enabled()) {
            return true; // master switch off (e.g. in tests that don't exercise limiting)
        }
        String path = request.getRequestURI();
        return path == null || !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String clientKey = resolveClientKey(request);

        // Atomically take one token. The probe tells us whether it was consumed, how many remain,
        // and (when refused) the nanoseconds until the next token becomes available.
        ConsumptionProbe probe = bucketProvider.resolveBucket(clientKey)
                .tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            // Allowed — advertise the remaining allowance so a well-behaved client can pace itself.
            response.setHeader("X-RateLimit-Remaining", Long.toString(probe.getRemainingTokens()));
            chain.doFilter(request, response);
            return;
        }

        // Throttled — reject at the edge with 429 + Retry-After + an RFC-7807 body.
        writeTooManyRequests(response, probe);
    }

    // Client identity for the bucket key:
    //   • X-API-Key header when present — a stable identity that survives IP changes and shared NATs.
    //   • otherwise the caller's IP (honouring X-Forwarded-For's first hop, since behind a proxy/LB
    //     the direct remote address is the proxy, not the real client).
    private String resolveClientKey(HttpServletRequest request) {
        String apiKey = request.getHeader("X-API-Key");
        if (StringUtils.hasText(apiKey)) {
            return "key:" + apiKey.trim();
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwardedFor)) {
            // X-Forwarded-For is a comma-separated chain; the first entry is the original client.
            return "ip:" + forwardedFor.split(",")[0].trim();
        }
        return "ip:" + request.getRemoteAddr();
    }

    // Build the 429 response: a Retry-After header (seconds, per RFC 7231) plus a problem+json body
    // (RFC 7807) matching the shape the rest of the API already returns via ApiExceptionHandler, so
    // clients parse one consistent error envelope everywhere.
    private void writeTooManyRequests(HttpServletResponse response, ConsumptionProbe probe)
            throws IOException {

        // Nanoseconds until a token refills → whole seconds, rounded UP so a client that waits the
        // advertised time is guaranteed at least one token (never told to retry a hair too early).
        long waitForRefillNanos = probe.getNanosToWaitForRefill();
        long retryAfterSeconds = Math.max(1, (long) Math.ceil(waitForRefillNanos / (double) TimeUnit.SECONDS.toNanos(1)));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS,
                "Rate limit exceeded. Try again in " + retryAfterSeconds + "s.");
        problem.setTitle("Too many requests");
        problem.setProperty("limit", props.capacity());
        problem.setProperty("retryAfterSeconds", retryAfterSeconds);
        problem.setProperty("timestamp", Instant.now());

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        // Retry-After (seconds) — the standard "back off this long" signal clients/proxies understand.
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        // Remaining is zero the moment we throttle — say so explicitly.
        response.setHeader("X-RateLimit-Remaining", "0");
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        // UTF-8, not the servlet default ISO-8859-1: JSON is UTF-8 and problem+json bodies may carry
        // non-ASCII. Set encoding before writing so the charset in the Content-Type is correct.
        response.setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), problem);
    }
}
