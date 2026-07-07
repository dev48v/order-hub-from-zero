package dev.dev48v.orderhub;

import dev.dev48v.orderhub.web.dto.CreateOrderRequest;
import dev.dev48v.orderhub.web.dto.OrderResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Day 13 — proves the rate limiter actually bites: fire capacity requests (all allowed), then one
// more (rejected). Runs the WHOLE app on a random port with a REAL Postgres + Redis (via
// AbstractPostgresIT), so the request travels through the real servlet filter and — because Redis is
// up — through the real Redis-backed distributed bucket, exactly as production would.
//
// @TestPropertySource pins a small, deterministic limit for this class only (capacity 5) so the test
// is fast and unambiguous, without affecting the other integration tests. Each test uses a UNIQUE
// X-API-Key so its bucket is isolated — no bleed between tests, and no dependence on the shared IP.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "app.ratelimit.enabled=true",
        "app.ratelimit.capacity=5",
        "app.ratelimit.refill-period=1m"   // long window so refill can't rescue the 6th call mid-test
})
class RateLimitIT extends AbstractPostgresIT {

    private static final int CAPACITY = 5;

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("The first CAPACITY requests pass; the CAPACITY+1th gets 429 + Retry-After + problem body")
    void nPlusOneRequestIsRateLimited() {
        HttpHeaders headers = apiKeyHeaders(); // one distinct client for this whole test

        // Fire exactly CAPACITY writes — every one must be allowed (201 Created) and should carry a
        // decreasing X-RateLimit-Remaining header.
        for (int i = 0; i < CAPACITY; i++) {
            HttpEntity<CreateOrderRequest> body =
                    new HttpEntity<>(new CreateOrderRequest("Ada", "Keyboard", 1), headers);
            ResponseEntity<OrderResponse> ok =
                    rest.postForEntity("/api/orders", body, OrderResponse.class);

            assertThat(ok.getStatusCode())
                    .as("request %d of %d should be allowed", i + 1, CAPACITY)
                    .isEqualTo(HttpStatus.CREATED);
            assertThat(ok.getHeaders().getFirst("X-RateLimit-Remaining"))
                    .as("allowed response advertises remaining tokens")
                    .isNotNull();
        }

        // The (CAPACITY+1)th request drains an empty bucket → 429.
        HttpEntity<CreateOrderRequest> overLimit =
                new HttpEntity<>(new CreateOrderRequest("Ada", "Keyboard", 1), headers);
        ResponseEntity<ProblemDetail> throttled =
                rest.postForEntity("/api/orders", overLimit, ProblemDetail.class);

        assertThat(throttled.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // A Retry-After header tells the client how long to back off (whole seconds, >= 1).
        String retryAfter = throttled.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        assertThat(retryAfter).as("Retry-After header present").isNotNull();
        assertThat(Long.parseLong(retryAfter)).isGreaterThanOrEqualTo(1);

        // Remaining is explicitly zero once throttled.
        assertThat(throttled.getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");

        // The body is an RFC-7807 ProblemDetail: 429, our title, and the limit surfaced as an extension.
        // isCompatibleWith ignores the charset parameter (the media type is application/problem+json).
        assertThat(throttled.getHeaders().getContentType()).isNotNull();
        assertThat(throttled.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .isTrue();
        ProblemDetail problem = throttled.getBody();
        assertThat(problem).isNotNull();
        assertThat(problem.getStatus()).isEqualTo(429);
        assertThat(problem.getTitle()).isEqualTo("Too many requests");
    }

    @Test
    @DisplayName("A different client (distinct API key) has its own bucket and is not affected")
    void limitIsPerClient() {
        // Exhaust client A completely.
        HttpHeaders clientA = apiKeyHeaders();
        for (int i = 0; i < CAPACITY; i++) {
            rest.exchange("/api/orders", HttpMethod.POST,
                    new HttpEntity<>(new CreateOrderRequest("A", "Item", 1), clientA), OrderResponse.class);
        }
        ResponseEntity<ProblemDetail> aBlocked = rest.postForEntity("/api/orders",
                new HttpEntity<>(new CreateOrderRequest("A", "Item", 1), clientA), ProblemDetail.class);
        assertThat(aBlocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // Client B, with its own key, still gets a full allowance — the limit is keyed per client.
        HttpHeaders clientB = apiKeyHeaders();
        ResponseEntity<OrderResponse> bOk = rest.postForEntity("/api/orders",
                new HttpEntity<>(new CreateOrderRequest("B", "Item", 1), clientB), OrderResponse.class);
        assertThat(bOk.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // A fresh, unique client identity so each test owns an isolated bucket.
    private HttpHeaders apiKeyHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-API-Key", "test-" + UUID.randomUUID());
        return headers;
    }
}
