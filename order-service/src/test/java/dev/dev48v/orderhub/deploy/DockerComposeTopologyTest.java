package dev.dev48v.orderhub.deploy;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Day 42 (Phase 6 — DEPLOY) — DEPLOYMENT TOPOLOGY guard.
 *
 * <p>This is a plain JUnit test — it does NOT need a Docker daemon and does NOT build images.
 * Building images is a CI/ops concern; here we only assert that the deployment ARTIFACTS we
 * checked in are well-formed and coherent, so a fat-fingered edit to the compose graph or a
 * missing Dockerfile fails the reactor build instead of silently breaking `docker compose up`.
 *
 * <p>It verifies two things:
 * <ol>
 *   <li>Every runnable service module has a multi-stage {@code <module>/Dockerfile}
 *       (a build stage {@code AS build}, a separate lean runtime stage, a non-root {@code USER}
 *       and an {@code EXPOSE}).</li>
 *   <li>The root {@code docker-compose.yml} parses as YAML and declares the whole stack:
 *       the six infrastructure services and all eight app services, each app service with a
 *       {@code build} (or {@code image}) and — except the root config-server — a
 *       {@code depends_on}, plus the gateway published on :8080.</li>
 * </ol>
 */
class DockerComposeTopologyTest {

    /** The eight runnable Spring Boot modules — each must ship a multi-stage Dockerfile. */
    private static final List<String> APP_SERVICES = List.of(
            "config-server", "eureka-server", "api-gateway", "order-service",
            "inventory-service", "payment-service", "shipping-service", "notification-service");

    /** The backing infrastructure the stack brings up alongside the services. */
    private static final List<String> INFRA_SERVICES = List.of(
            "kafka", "redis", "postgres", "prometheus", "grafana", "loki");

    // ── locate the repo root ────────────────────────────────────────────────
    // Surefire runs each module's tests with the MODULE dir as the working dir, so walk up from
    // there to the first ancestor that contains docker-compose.yml (the reactor root).
    private static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("docker-compose.yml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return fail("Could not locate repo root (no docker-compose.yml found walking up from "
                + System.getProperty("user.dir") + ")");
    }

    @Test
    void everyServiceModuleHasAMultiStageDockerfile() throws IOException {
        Path root = repoRoot();
        for (String service : APP_SERVICES) {
            Path dockerfile = root.resolve(service).resolve("Dockerfile");
            assertTrue(Files.exists(dockerfile),
                    "Missing Dockerfile for module '" + service + "' at " + dockerfile);

            String body = Files.readString(dockerfile, StandardCharsets.UTF_8);

            long fromStages = body.lines()
                    .map(String::trim)
                    .filter(l -> l.regionMatches(true, 0, "FROM ", 0, 5))
                    .count();
            assertTrue(fromStages >= 2,
                    service + "/Dockerfile is not multi-stage — expected >= 2 FROM stages, found " + fromStages);

            assertTrue(body.toLowerCase().contains(" as build"),
                    service + "/Dockerfile has no named build stage (expected 'AS build')");
            assertTrue(body.contains("USER spring"),
                    service + "/Dockerfile does not drop to a non-root user (expected 'USER spring')");
            assertTrue(body.contains("EXPOSE "),
                    service + "/Dockerfile does not EXPOSE a port");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void composeFileDeclaresTheWholeStack() throws IOException {
        Path compose = repoRoot().resolve("docker-compose.yml");
        assertTrue(Files.exists(compose), "docker-compose.yml is missing at repo root");

        Map<String, Object> doc;
        try (Reader r = Files.newBufferedReader(compose, StandardCharsets.UTF_8)) {
            doc = new Yaml().load(r);   // throws if the YAML is malformed
        }
        assertNotNull(doc, "docker-compose.yml parsed to null (empty or invalid)");

        Object servicesNode = doc.get("services");
        assertTrue(servicesNode instanceof Map, "docker-compose.yml has no 'services' map");
        Map<String, Object> services = (Map<String, Object>) servicesNode;

        // Infra services all present.
        for (String infra : INFRA_SERVICES) {
            assertTrue(services.containsKey(infra),
                    "docker-compose.yml is missing infrastructure service '" + infra + "'");
        }

        // Every app service present, buildable, and (except the root config-server) waits on something.
        for (String app : APP_SERVICES) {
            Object node = services.get(app);
            assertTrue(node instanceof Map, "docker-compose.yml is missing app service '" + app + "'");
            Map<String, Object> svc = (Map<String, Object>) node;

            assertTrue(svc.containsKey("build") || svc.containsKey("image"),
                    "app service '" + app + "' has neither a build nor an image");

            if (!"config-server".equals(app)) {
                Object dependsOn = svc.get("depends_on");
                assertTrue(dependsOn != null && !((Map<String, Object>) dependsOn).isEmpty(),
                        "app service '" + app + "' declares no depends_on");
            }
        }

        // The gateway is the single public front door — it must publish port 8080 to the host.
        Map<String, Object> gateway = (Map<String, Object>) services.get("api-gateway");
        Object ports = gateway.get("ports");
        assertTrue(ports instanceof List && ((List<Object>) ports).stream()
                        .map(String::valueOf).anyMatch(p -> p.contains("8080:8080")),
                "api-gateway does not publish the public port 8080");
    }
}
