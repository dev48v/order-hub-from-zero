package dev.dev48v.orderhub.persistence;

import dev.dev48v.orderhub.domain.Order;
import dev.dev48v.orderhub.domain.OrderStatus;
import dev.dev48v.orderhub.repository.OrderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Day 9 — an integration test for the REAL persistence adapter against a REAL PostgreSQL.
// WHY: the unit tests (Day 8) mocked the repository, so they never proved the SQL, the
// Hibernate mapping or the Flyway migrations actually work. Here Testcontainers boots a
// throwaway PostgreSQL in Docker, @DynamicPropertySource points Spring's datasource at it,
// and @SpringBootTest wires the genuine JpaOrderRepository -> SpringDataOrderRepository ->
// Hibernate -> Postgres. So this exercises the same engine prod uses, not the H2 default:
// Flyway runs V1/V2 on the container, then we save and read orders for real.
// Day 19 — this IT boots the FULL app context (to wire the real JPA adapter), which now includes the
// Eureka client. Disable discovery for the test: this class exercises persistence only and never calls
// inventory-service, so there's nothing to resolve, and switching Eureka off keeps the boot fast and
// free of registration/heartbeat noise against a registry that isn't running in the build.
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.discovery.enabled=false",
        // Day 28 — this full-app IT tests persistence only; keep the saga's two Kafka consumers from starting
        // against a broker that isn't running in the build (the saga is proven in OrderSagaChoreographyTest).
        "orderhub.saga.enabled=false"
})
@Testcontainers
class OrderRepositoryIT {

    // One container shared by every test method in this class (static => started once).
    // "postgres:16-alpine" is a small, real Postgres image pulled by Docker on first run.
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // Rewire Spring's datasource onto the container BEFORE the context starts, so Flyway and
    // Hibernate connect to Postgres (not the H2 defaults in application.yml). The driver is
    // forced to Postgres too, otherwise application.yml's org.h2.Driver would still win.
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    // The production adapter (JpaOrderRepository implements OrderRepository, the default bean).
    @Autowired
    private OrderRepository repository;

    // Used only to prove we are really on Postgres and that Flyway created the schema.
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("the container is a real, running PostgreSQL")
    void containerIsRunningPostgres() {
        assertThat(postgres.isRunning()).isTrue();

        String product = jdbcTemplate.queryForObject(
                "SELECT current_setting('server_version')", String.class);
        assertThat(product).isNotBlank();
    }

    @Test
    @DisplayName("Flyway migrations ran on the container: the orders table exists")
    void flywayCreatedTheOrdersTable() {
        // information_schema is portable SQL; on H2 this would also work, so we additionally
        // assert Flyway's own history table recorded our two migrations (V1 + V2).
        Integer ordersTables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'orders'",
                Integer.class);
        assertThat(ordersTables).isEqualTo(1);

        Integer migrations = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true",
                Integer.class);
        assertThat(migrations).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("save then findById returns the persisted order from Postgres")
    void saveThenFindByIdRoundTrips() {
        Order toSave = new Order(UUID.randomUUID().toString(), "Ada", "Keyboard", 3);

        Order saved = repository.save(toSave);
        assertThat(saved.getId()).isEqualTo(toSave.getId());

        Optional<Order> found = repository.findById(toSave.getId());
        assertThat(found).isPresent();
        Order order = found.orElseThrow();
        assertThat(order.getCustomer()).isEqualTo("Ada");
        assertThat(order.getItem()).isEqualTo("Keyboard");
        assertThat(order.getQuantity()).isEqualTo(3);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PLACED);
        assertThat(order.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("findById on an unknown id returns empty")
    void findByIdMissingIsEmpty() {
        assertThat(repository.findById("does-not-exist")).isEmpty();
    }

    @Test
    @DisplayName("findAll returns every saved order")
    void findAllReturnsSavedOrders() {
        String id1 = UUID.randomUUID().toString();
        String id2 = UUID.randomUUID().toString();
        repository.save(new Order(id1, "Babbage", "Mouse", 1));
        repository.save(new Order(id2, "Lovelace", "Monitor", 2));

        assertThat(repository.findAll())
                .extracting(Order::getId)
                .contains(id1, id2);
    }

    @Test
    @DisplayName("paged search with no filter returns a bounded page from Postgres")
    void pagedSearchUnfiltered() {
        for (int i = 0; i < 5; i++) {
            repository.save(new Order(UUID.randomUUID().toString(), "Cust" + i, "Widget", 1));
        }

        Page<Order> page = repository.search(null, PageRequest.of(0, 2));

        assertThat(page.getSize()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(5);
    }

    @Test
    @DisplayName("paged search filtered by status only returns matching orders")
    void pagedSearchFilteredByStatus() {
        // A freshly saved order is PLACED; confirm one so we have a CONFIRMED row too.
        Order placed = repository.save(
                new Order(UUID.randomUUID().toString(), "Hopper", "Cable", 1));

        Order confirmedSeed = new Order(UUID.randomUUID().toString(), "Turing", "Disk", 1);
        confirmedSeed.confirm();
        repository.save(confirmedSeed);

        Page<Order> confirmed = repository.search(OrderStatus.CONFIRMED, PageRequest.of(0, 50));

        assertThat(confirmed.getContent())
                .isNotEmpty()
                .allSatisfy(o -> assertThat(o.getStatus()).isEqualTo(OrderStatus.CONFIRMED));
        assertThat(confirmed.getContent())
                .extracting(Order::getId)
                .doesNotContain(placed.getId());
    }
}
