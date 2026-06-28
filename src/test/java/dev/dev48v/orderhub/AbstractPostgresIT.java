package dev.dev48v.orderhub;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

// Day 9 — shared base for integration tests that need a REAL PostgreSQL.
// WHY: both the repository IT and the full-stack API IT want the same throwaway Postgres
// container wired onto Spring's datasource. Putting the @Container + @DynamicPropertySource
// here (instead of copy-pasting) keeps each test focused on its assertions. The container is
// static, so JUnit/Testcontainers starts it once per class that extends this base; the driver
// is forced to Postgres so application.yml's H2 default never wins.
@Testcontainers
public abstract class AbstractPostgresIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }
}
