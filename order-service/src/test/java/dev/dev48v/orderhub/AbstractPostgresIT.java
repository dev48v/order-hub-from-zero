package dev.dev48v.orderhub;

import dev.dev48v.orderhub.inventory.InventoryServiceClient;
import org.springframework.boot.test.mock.mockito.MockBean;
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
    }
}
