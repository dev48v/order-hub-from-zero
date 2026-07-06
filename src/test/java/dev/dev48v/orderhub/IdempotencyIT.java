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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// Day 16 — proves idempotency keys make POST /api/orders safe to retry, end to end: the whole app on
// a random port with a REAL Postgres + Redis (via AbstractPostgresIT), so a request travels through
// the real IdempotencyAspect and the real Redis-backed store, exactly as production would.
//
// Rate limiting is switched off for this class so the many POSTs (which all share one client IP)
// aren't throttled; that mechanism has its own test (RateLimitIT). Each test uses a UNIQUE key so the
// shared Redis can't leak state between tests.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "app.idempotency.enabled=true",
        "app.ratelimit.enabled=false"
})
class IdempotencyIT extends AbstractPostgresIT {

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("Same Idempotency-Key twice → ONE order created, the same response replayed")
    void sameKeyReturnsSameOrder() {
        String key = UUID.randomUUID().toString();
        CreateOrderRequest body = new CreateOrderRequest("Ada", "Keyboard", 2);

        ResponseEntity<OrderResponse> first = post(body, key);
        ResponseEntity<OrderResponse> second = post(body, key);

        // Both succeed with 201 (the replay reproduces the original status), and — crucially — carry
        // the SAME order id: the second call did NOT create a new order, it replayed the first's.
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody()).isNotNull();
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().id()).isEqualTo(first.getBody().id());

        // The replay is flagged for observability; the fresh response is not.
        assertThat(first.getHeaders().getFirst("Idempotency-Replayed")).isNull();
        assertThat(second.getHeaders().getFirst("Idempotency-Replayed")).isEqualTo("true");

        // And the single created order really exists and is unchanged when fetched directly.
        OrderResponse fetched = rest.getForObject("/api/orders/" + first.getBody().id(), OrderResponse.class);
        assertThat(fetched).isNotNull();
        assertThat(fetched.customer()).isEqualTo("Ada");
        assertThat(fetched.quantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("A different key → a brand-new, distinct order")
    void differentKeyCreatesNewOrder() {
        CreateOrderRequest body = new CreateOrderRequest("Babbage", "Mouse", 1);

        ResponseEntity<OrderResponse> a = post(body, UUID.randomUUID().toString());
        ResponseEntity<OrderResponse> b = post(body, UUID.randomUUID().toString());

        assertThat(a.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(b.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(a.getBody()).isNotNull();
        assertThat(b.getBody()).isNotNull();
        // Distinct keys → two independent orders with different ids.
        assertThat(b.getBody().id()).isNotEqualTo(a.getBody().id());
    }

    @Test
    @DisplayName("Reusing a key with a DIFFERENT body → 422 Unprocessable Entity")
    void reusingKeyWithDifferentBodyIsRejected() {
        String key = UUID.randomUUID().toString();

        ResponseEntity<OrderResponse> first = post(new CreateOrderRequest("Ada", "Keyboard", 2), key);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Same key, different payload → the client bug is refused, not mis-served.
        ResponseEntity<ProblemDetail> reused =
                postForProblem(new CreateOrderRequest("Ada", "Keyboard", 99), key);
        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(reused.getBody()).isNotNull();
        assertThat(reused.getBody().getTitle()).isEqualTo("Idempotency-Key reused with a different request");
    }

    @Test
    @DisplayName("Many concurrent requests with the SAME key → at most ONE order created")
    void concurrentSameKeyCreatesAtMostOneOrder() throws InterruptedException {
        String key = UUID.randomUUID().toString();
        CreateOrderRequest body = new CreateOrderRequest("Concurrent", "Widget", 3);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        // Ids returned by any 2xx response — a Set so duplicates collapse.
        Set<String> createdIds = ConcurrentHashMap.newKeySet();

        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();                       // all fire at (nearly) the same instant
                        ResponseEntity<OrderResponse> res = post(body, key);
                        if (res.getStatusCode().is2xxSuccessful() && res.getBody() != null) {
                            createdIds.add(res.getBody().id());
                        }
                    } catch (Exception ignored) {
                        // A losing racer may get 409 (in-flight); that's expected and not an error.
                    }
                });
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();                                // release the herd
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }

        // The whole point: however the race resolved, every successful response points at the SAME
        // single order — no concurrent duplicate was ever created.
        assertThat(createdIds).as("all 2xx responses share one order id").hasSize(1);

        // That one order is real and fetchable.
        String id = createdIds.iterator().next();
        OrderResponse fetched = rest.getForObject("/api/orders/" + id, OrderResponse.class);
        assertThat(fetched).isNotNull();
        assertThat(fetched.customer()).isEqualTo("Concurrent");
    }

    // --- helpers ---

    private ResponseEntity<OrderResponse> post(CreateOrderRequest body, String key) {
        return rest.postForEntity("/api/orders", new HttpEntity<>(body, headers(key)), OrderResponse.class);
    }

    private ResponseEntity<ProblemDetail> postForProblem(CreateOrderRequest body, String key) {
        return rest.postForEntity("/api/orders", new HttpEntity<>(body, headers(key)), ProblemDetail.class);
    }

    private HttpHeaders headers(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        return headers;
    }
}
