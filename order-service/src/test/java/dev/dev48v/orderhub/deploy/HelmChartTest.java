package dev.dev48v.orderhub.deploy;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Day 44 (Phase 6 — DEPLOY) — HELM CHART guard.
 *
 * <p>The Day-44 sibling of {@link KubernetesManifestTest}. Where Day 43 hand-wrote flat manifests,
 * Day 44 templatizes them into a Helm chart under {@code helm/orderhub/}. There is no {@code helm}
 * binary on this build box, so instead of {@code helm lint} / {@code helm template} this test does
 * the CI-friendly equivalent: it parses the chart's two PLAIN-YAML inputs — {@code Chart.yaml} and
 * {@code values.yaml} — with SnakeYAML (no live cluster, nothing applied) and asserts the chart is
 * well-formed and reproduces the Day-43 topology.
 *
 * <p>The Go-templated files under {@code templates/} are intentionally NOT parsed here (they contain
 * {@code {{ ... }}} directives that are not valid YAML until helm renders them); the invariants that
 * matter — chart metadata, the eight services with image+port, the ingress routing, and that the
 * secrets are placeholders only — all live in the two plain-YAML files this test reads.
 *
 * <p>Specifically it verifies:
 * <ol>
 *   <li>{@code Chart.yaml} declares {@code apiVersion: v2}, {@code name: orderhub}, and a
 *       {@code version} + {@code appVersion};</li>
 *   <li>{@code values.yaml} declares all EIGHT app services, each with an {@code image} and the
 *       right {@code port} (matching the Day-42 build / Day-43 manifests);</li>
 *   <li>the {@code ingress} block has a {@code host} and at least the order + inventory routes;</li>
 *   <li>the {@code secrets} are base64 PLACEHOLDERS only — every value decodes to a
 *       {@code change-me-…} string (or the {@code orderhub} username), never a real secret.</li>
 * </ol>
 */
class HelmChartTest {

    /** The eight runnable Spring Boot modules → their real HTTP port (same map the k8s test asserts). */
    private static final Map<String, Integer> APP_PORTS = Map.of(
            "config-server", 8888,
            "eureka-server", 8761,
            "api-gateway", 8080,
            "order-service", 8082,
            "inventory-service", 8081,
            "payment-service", 8083,
            "shipping-service", 8084,
            "notification-service", 8085);

    // ── locate the chart directory by walking up from the module's working dir ──────────────
    private static Path chartDir() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            Path chart = dir.resolve("helm").resolve("orderhub");
            if (Files.exists(chart.resolve("Chart.yaml")) && Files.exists(chart.resolve("values.yaml"))) {
                return chart;
            }
            dir = dir.getParent();
        }
        return fail("Could not locate helm/orderhub (Chart.yaml + values.yaml) walking up from "
                + System.getProperty("user.dir"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(String fileName) throws IOException {
        Path f = chartDir().resolve(fileName);
        assertTrue(Files.exists(f), "missing chart file " + fileName);
        try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            Object o = new Yaml().load(r);   // throws on malformed YAML
            assertTrue(o instanceof Map, fileName + " is not a YAML mapping");
            return (Map<String, Object>) o;
        }
    }

    @Test
    void chartMetadataIsPresentAndWellFormed() throws IOException {
        Map<String, Object> chart = loadYaml("Chart.yaml");
        assertEquals("v2", String.valueOf(chart.get("apiVersion")),
                "Chart.yaml must be a Helm 3 (apiVersion v2) chart");
        assertEquals("orderhub", String.valueOf(chart.get("name")), "chart name must be 'orderhub'");
        assertNotNull(chart.get("version"), "Chart.yaml is missing a chart 'version'");
        assertNotNull(chart.get("appVersion"), "Chart.yaml is missing an 'appVersion'");
        assertEquals("0.1.0", String.valueOf(chart.get("version")), "chart version should be 0.1.0");
        assertEquals("0.1.0", String.valueOf(chart.get("appVersion")), "appVersion should track the 0.1.0 images");
    }

    @Test
    @SuppressWarnings("unchecked")
    void allEightServicesAreDeclaredWithImageAndPort() throws IOException {
        Map<String, Object> values = loadYaml("values.yaml");
        Object servicesNode = values.get("services");
        assertTrue(servicesNode instanceof Map, "values.yaml has no 'services' map");
        Map<String, Object> services = (Map<String, Object>) servicesNode;

        // Exactly the eight apps — no more, no fewer.
        assertEquals(APP_PORTS.keySet(), services.keySet(),
                "values.yaml services do not match the eight app modules");

        for (Map.Entry<String, Integer> e : APP_PORTS.entrySet()) {
            String app = e.getKey();
            int port = e.getValue();
            Object svcNode = services.get(app);
            assertTrue(svcNode instanceof Map, "service '" + app + "' is not a mapping");
            Map<String, Object> svc = (Map<String, Object>) svcNode;

            Object image = svc.get("image");
            assertNotNull(image, app + " is missing an 'image'");
            assertTrue(String.valueOf(image).startsWith("orderhub/"),
                    app + " image should be the Day-42 build image (orderhub/…), was " + image);
            assertEquals(port, svc.get("port"), app + " declares the wrong port");
            assertNotNull(svc.get("replicas"), app + " is missing 'replicas'");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void ingressHasAHostAndTheApiRoutes() throws IOException {
        Map<String, Object> values = loadYaml("values.yaml");
        Object ingressNode = values.get("ingress");
        assertTrue(ingressNode instanceof Map, "values.yaml has no 'ingress' block");
        Map<String, Object> ingress = (Map<String, Object>) ingressNode;

        assertEquals(Boolean.TRUE, ingress.get("enabled"), "ingress should be enabled by default");
        Object host = ingress.get("host");
        assertNotNull(host, "ingress has no 'host'");
        assertFalse(String.valueOf(host).isBlank(), "ingress host is blank");
        assertNotNull(ingress.get("className"), "ingress has no 'className'");

        // The gateway is the default (catch-all) backend.
        Object defaultBackend = ingress.get("defaultBackend");
        assertTrue(defaultBackend instanceof Map, "ingress has no defaultBackend");
        assertEquals("api-gateway", String.valueOf(((Map<String, Object>) defaultBackend).get("service")),
                "the ingress default backend should be the api-gateway");

        // At least the order + inventory routes must be present and point at the right services.
        Object routesNode = ingress.get("routes");
        assertTrue(routesNode instanceof List, "ingress has no 'routes' list");
        List<Object> routes = (List<Object>) routesNode;
        assertEquals("order-service", serviceForPath(routes, "/api/orders"),
                "/api/orders must route to order-service");
        assertEquals("inventory-service", serviceForPath(routes, "/api/inventory"),
                "/api/inventory must route to inventory-service");
    }

    private static String serviceForPath(List<Object> routes, String path) {
        for (Object o : routes) {
            Map<?, ?> route = (Map<?, ?>) o;
            if (path.equals(String.valueOf(route.get("path")))) {
                return String.valueOf(route.get("service"));
            }
        }
        return fail("no ingress route for path " + path);
    }

    @Test
    @SuppressWarnings("unchecked")
    void secretsAreBase64PlaceholdersNotRealValues() throws IOException {
        Map<String, Object> values = loadYaml("values.yaml");
        Object secretsNode = values.get("secrets");
        assertTrue(secretsNode instanceof Map, "values.yaml has no 'secrets' block");
        Map<String, Object> secrets = (Map<String, Object>) secretsNode;
        Object dataNode = secrets.get("data");
        assertTrue(dataNode instanceof Map, "secrets block has no 'data'");
        Map<String, Object> data = (Map<String, Object>) dataNode;

        // The expected placeholder keys are all present.
        for (String key : List.of("JWT_SECRET", "SERVICE_TOKEN",
                "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD")) {
            assertTrue(data.containsKey(key), "secrets.data is missing key '" + key + "'");
        }

        // Every value must base64-decode to a KNOWN placeholder — a "change-me-…" string, or the
        // "orderhub" username. Anything else would mean a real secret leaked into the chart.
        for (Map.Entry<String, Object> e : data.entrySet()) {
            String decoded = decodeBase64(e.getKey(), String.valueOf(e.getValue()));
            boolean placeholder = decoded.startsWith("change-me") || decoded.equals("orderhub");
            assertTrue(placeholder,
                    "secrets." + e.getKey() + " decodes to a NON-placeholder value — real secrets must "
                            + "never be committed to the chart (decoded a value that is not 'change-me-…' / 'orderhub')");
        }
    }

    private static String decodeBase64(String key, String raw) {
        try {
            return new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return fail("secrets." + key + " is not valid base64: " + raw);
        }
    }
}
