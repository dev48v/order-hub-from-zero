package dev.dev48v.orderhub;

import dev.dev48v.orderhub.inventory.InventoryServiceClient;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// Day 9 — shared base for integration tests that need a REAL PostgreSQL.
// WHY: both the repository IT and the full-stack API IT want the same throwaway Postgres
// container wired onto Spring's datasource. Putting the @Container + @DynamicPropertySource
// here (instead of copy-pasting) keeps each test focused on its assertions. The container is
// static, so JUnit/Testcontainers starts it once per class that extends this base; the driver
// is forced to Postgres so application.yml's H2 default never wins.
//
// Day 11 — the base also boots a throwaway REDIS container and points spring.data.redis.* at it.
// WHY: the caching layer (@Cacheable/@CacheEvict on OrderService) needs a live Redis to store into.
// Rather than mock the cache away, we give the integration test the real thing — same as prod — so
// the full-stack test genuinely proves a read gets cached and a write evicts it. Mirroring the
// Postgres pattern keeps the tests hermetic (no external services, no local Redis install needed).
@Testcontainers
public abstract class AbstractPostgresIT {

    // Day 18 — order-service now reserves stock in inventory-service over HTTP (OpenFeign) when an order
    // is placed. These full-stack ITs exercise the order flow but must run WITHOUT a real inventory-service
    // (and without Docker for it). @MockBean replaces the Feign proxy with a Mockito mock for every test
    // that extends this base, so placeOrder's reservation call is a no-op that returns a null StockView
    // (the flow tolerates it) instead of hitting the network. The real client's HTTP behaviour is proven
    // separately in InventoryServiceClientTest against a MockWebServer.
    @MockBean
    protected InventoryServiceClient inventoryServiceClient;

    // Day 19 — pin the test HTTP client to the JDK's SimpleClientHttpRequestFactory.
    // WHY: the Eureka client starter (added today) transitively puts Apache HttpClient 5 on the
    // classpath — spring-cloud-netflix-eureka-client REQUIRES it for its own REST transport, so it
    // can't be excluded. But Spring Boot AUTO-DETECTS HC5 and, from now on, builds the auto-configured
    // TestRestTemplate on top of it. HC5 keep-alives + transparently RETRIES a request when a pooled
    // connection is found stale — which silently breaks RateLimitIT: the throttled 6th POST gets
    // retried, and by the time the retry runs a token has refilled, so the client sees 201 instead of
    // the 429 the server actually returned. The order flow uses Feign (never RestTemplate) in
    // production, so this is purely a test-client concern. Forcing the simple, non-pooling JDK factory
    // restores the deterministic Day-18 behaviour: one request in, one response out, no hidden retry.
    @Autowired(required = false)
    private TestRestTemplate restTemplate;

    @BeforeEach
    void useNonRetryingHttpClient() {
        if (restTemplate != null) {
            restTemplate.getRestTemplate().setRequestFactory(new SimpleClientHttpRequestFactory());
        }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        // Point Spring Boot's Redis auto-config at the container's mapped host/port.
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        // Day 19 — the Eureka client is now on the classpath. These full-stack ITs boot the whole app,
        // which would otherwise try to register with (and fetch a registry from) a Eureka server that
        // isn't running in the build. Turn discovery OFF for the tests: the inventory call is already a
        // @MockBean above, so no name ever needs resolving, and this keeps the context fast and quiet
        // (no background registration/heartbeat threads, no connection-refused log spam).
        registry.add("eureka.client.enabled", () -> "false");
        registry.add("spring.cloud.discovery.enabled", () -> "false");

        // Day 25 — order-service now PUBLISHES an OrderPlaced event to Kafka after each order create. These
        // full-stack ITs place orders but aren't testing Kafka and have no broker in the build, so turn
        // event publishing OFF here: placeOrder behaves exactly as before and never reaches for a broker,
        // keeping the tests hermetic and fast. The producer itself is proven separately, against an embedded
        // broker, in OrderPlacedEventIT.
        registry.add("orderhub.events.enabled", () -> "false");
    }
}
