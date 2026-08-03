package dev.dev48v.orderhub.deploy;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Day 43 (Phase 6 — DEPLOY) — KUBERNETES MANIFEST guard.
 *
 * <p>The sibling of Day 42's {@link DockerComposeTopologyTest}: a plain JUnit + SnakeYAML test that
 * needs NO live cluster and applies nothing — it parses every checked-in {@code k8s/*.yaml} manifest
 * and asserts the deployment set is well-formed and coherent, so a fat-fingered probe path, a wrong
 * image tag, a mismatched port, or a dropped ConfigMap/Secret reference fails the reactor build
 * instead of surfacing only at {@code kubectl apply} time.
 *
 * <p>For each of the eight runnable app services it verifies:
 * <ol>
 *   <li>a {@code Deployment} AND a {@code ClusterIP Service} exist;</li>
 *   <li>the container image is the Day-42 build image {@code orderhub/<svc>:0.1.0} and its
 *       {@code containerPort} (and the Service port) match the service's real port;</li>
 *   <li>the Deployment declares BOTH a liveness probe on {@code /actuator/health/liveness} AND a
 *       readiness probe on {@code /actuator/health/readiness} (plus a startup probe on an actuator
 *       path), each pointed at the container port;</li>
 *   <li>the Deployment consumes the shared {@code orderhub-config} ConfigMap and the
 *       {@code orderhub-secrets} Secret via {@code envFrom}.</li>
 * </ol>
 * Plus: the ConfigMap and the Secret objects themselves exist, and the Secret carries the expected
 * (placeholder) keys.
 */
class KubernetesManifestTest {

    /** The eight runnable Spring Boot modules → their real HTTP port (matches Day-42 EXPOSE / compose). */
    private static final Map<String, Integer> APP_PORTS = Map.of(
            "config-server", 8888,
            "eureka-server", 8761,
            "api-gateway", 8080,
            "order-service", 8082,
            "inventory-service", 8081,
            "payment-service", 8083,
            "shipping-service", 8084,
            "notification-service", 8085);

    private static final String CONFIG_MAP_NAME = "orderhub-config";
    private static final String SECRET_NAME = "orderhub-secrets";
    private static final String READINESS_PATH = "/actuator/health/readiness";
    private static final String LIVENESS_PATH = "/actuator/health/liveness";

    // ── locate the repo root (same walk-up as the Day-42 test) ──────────────────────────────
    private static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("docker-compose.yml")) && Files.isDirectory(dir.resolve("k8s"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return fail("Could not locate repo root (no docker-compose.yml + k8s/ walking up from "
                + System.getProperty("user.dir") + ")");
    }

    /** Parse every k8s/*.yaml file into a flat list of YAML documents (Deployments, Services, etc.). */
    private static List<Map<String, Object>> loadManifests() throws IOException {
        Path k8s = repoRoot().resolve("k8s");
        List<Map<String, Object>> docs = new ArrayList<>();
        Yaml yaml = new Yaml();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(k8s)) {
            s.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".yaml") || n.endsWith(".yml");
            }).sorted().forEach(files::add);
        }
        assertTrue(!files.isEmpty(), "no YAML manifests found under k8s/");
        for (Path f : files) {
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                for (Object o : yaml.loadAll(r)) {   // loadAll → each --- document; throws on malformed YAML
                    if (o instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> doc = (Map<String, Object>) o;
                        docs.add(doc);
                    }
                }
            }
        }
        return docs;
    }

    private static String kind(Map<String, Object> doc) {
        return String.valueOf(doc.get("kind"));
    }

    @SuppressWarnings("unchecked")
    private static String name(Map<String, Object> doc) {
        Object md = doc.get("metadata");
        return md instanceof Map ? String.valueOf(((Map<String, Object>) md).get("name")) : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> byKindAndName(List<Map<String, Object>> docs, String kind, String name) {
        return docs.stream()
                .filter(d -> kind.equals(kind(d)) && name.equals(name(d)))
                .findFirst().orElse(null);
    }

    /** Dig out the first container of a Deployment: spec.template.spec.containers[0]. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstContainer(Map<String, Object> deployment) {
        Map<String, Object> spec = (Map<String, Object>) deployment.get("spec");
        Map<String, Object> template = (Map<String, Object>) spec.get("template");
        Map<String, Object> podSpec = (Map<String, Object>) template.get("spec");
        List<Object> containers = (List<Object>) podSpec.get("containers");
        assertTrue(containers != null && !containers.isEmpty(), "deployment has no containers");
        return (Map<String, Object>) containers.get(0);
    }

    @SuppressWarnings("unchecked")
    private static void assertProbe(Map<String, Object> container, String probeKey, String expectedPath, int expectedPort) {
        Object probeNode = container.get(probeKey);
        assertTrue(probeNode instanceof Map, "container is missing a " + probeKey);
        Map<String, Object> probe = (Map<String, Object>) probeNode;
        Object httpGetNode = probe.get("httpGet");
        assertTrue(httpGetNode instanceof Map, probeKey + " is not an httpGet probe");
        Map<String, Object> httpGet = (Map<String, Object>) httpGetNode;
        assertEquals(expectedPath, httpGet.get("path"),
                probeKey + " points at the wrong path");
        assertEquals(expectedPort, httpGet.get("port"),
                probeKey + " points at the wrong port");
    }

    @Test
    void everyAppHasADeploymentAndClusterIpServiceThatLineUp() throws IOException {
        List<Map<String, Object>> docs = loadManifests();

        for (Map.Entry<String, Integer> e : APP_PORTS.entrySet()) {
            String app = e.getKey();
            int port = e.getValue();

            // 1. Deployment + Service both exist.
            Map<String, Object> deployment = byKindAndName(docs, "Deployment", app);
            assertNotNull(deployment, "missing Deployment for app '" + app + "'");
            Map<String, Object> service = byKindAndName(docs, "Service", app);
            assertNotNull(service, "missing Service for app '" + app + "'");

            // 2. Service is a ClusterIP publishing the service's port.
            @SuppressWarnings("unchecked")
            Map<String, Object> svcSpec = (Map<String, Object>) service.get("spec");
            assertEquals("ClusterIP", svcSpec.get("type"), app + " Service is not ClusterIP");
            @SuppressWarnings("unchecked")
            List<Object> svcPorts = (List<Object>) svcSpec.get("ports");
            assertTrue(svcPorts != null && svcPorts.stream()
                            .map(p -> ((Map<?, ?>) p).get("port"))
                            .anyMatch(p -> Integer.valueOf(port).equals(p)),
                    app + " Service does not publish port " + port);

            // 3. Container image + port line up with the Day-42 build.
            Map<String, Object> container = firstContainer(deployment);
            assertEquals("orderhub/" + app + ":0.1.0", container.get("image"),
                    app + " uses the wrong image");
            @SuppressWarnings("unchecked")
            List<Object> cPorts = (List<Object>) container.get("ports");
            assertTrue(cPorts != null && cPorts.stream()
                            .map(p -> ((Map<?, ?>) p).get("containerPort"))
                            .anyMatch(p -> Integer.valueOf(port).equals(p)),
                    app + " containerPort is not " + port);

            // 4. Liveness + readiness (+ startup) probes wired to the Actuator health groups.
            assertProbe(container, "readinessProbe", READINESS_PATH, port);
            assertProbe(container, "livenessProbe", LIVENESS_PATH, port);
            Object startup = container.get("startupProbe");
            assertTrue(startup instanceof Map, app + " has no startupProbe");
            @SuppressWarnings("unchecked")
            Map<String, Object> startupHttp = (Map<String, Object>) ((Map<String, Object>) startup).get("httpGet");
            assertTrue(startupHttp != null && String.valueOf(startupHttp.get("path")).startsWith("/actuator/health"),
                    app + " startupProbe does not target an actuator health path");

            // 5. The Deployment consumes the shared ConfigMap AND Secret via envFrom.
            Set<String> configMapRefs = new HashSet<>();
            Set<String> secretRefs = new HashSet<>();
            Object envFromNode = container.get("envFrom");
            if (envFromNode instanceof List) {
                for (Object ef : (List<?>) envFromNode) {
                    Map<?, ?> efMap = (Map<?, ?>) ef;
                    Object cmRef = efMap.get("configMapRef");
                    Object secRef = efMap.get("secretRef");
                    if (cmRef instanceof Map) configMapRefs.add(String.valueOf(((Map<?, ?>) cmRef).get("name")));
                    if (secRef instanceof Map) secretRefs.add(String.valueOf(((Map<?, ?>) secRef).get("name")));
                }
            }
            assertTrue(configMapRefs.contains(CONFIG_MAP_NAME),
                    app + " does not envFrom the '" + CONFIG_MAP_NAME + "' ConfigMap");
            assertTrue(secretRefs.contains(SECRET_NAME),
                    app + " does not envFrom the '" + SECRET_NAME + "' Secret");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void sharedConfigMapAndSecretAreDeclaredWithTheExpectedKeys() throws IOException {
        List<Map<String, Object>> docs = loadManifests();

        // The shared ConfigMap exists and carries the k8s profile switch + service addresses.
        Map<String, Object> configMap = byKindAndName(docs, "ConfigMap", CONFIG_MAP_NAME);
        assertNotNull(configMap, "missing ConfigMap '" + CONFIG_MAP_NAME + "'");
        Map<String, Object> cmData = (Map<String, Object>) configMap.get("data");
        assertNotNull(cmData, CONFIG_MAP_NAME + " has no data");
        assertEquals("k8s", cmData.get("SPRING_PROFILES_ACTIVE"),
                CONFIG_MAP_NAME + " must set SPRING_PROFILES_ACTIVE=k8s to activate the probe profile");

        // The Secret exists with base64 PLACEHOLDER keys for JWT / service token / DB creds.
        Map<String, Object> secret = byKindAndName(docs, "Secret", SECRET_NAME);
        assertNotNull(secret, "missing Secret '" + SECRET_NAME + "'");
        Map<String, Object> secData = (Map<String, Object>) secret.get("data");
        assertNotNull(secData, SECRET_NAME + " has no data");
        for (String key : List.of("JWT_SECRET", "SERVICE_TOKEN",
                "SPRING_DATASOURCE_USERNAME", "SPRING_DATASOURCE_PASSWORD")) {
            assertTrue(secData.containsKey(key), SECRET_NAME + " is missing key '" + key + "'");
        }
    }

    @Test
    void everyDeploymentPointsItsProbesAtActuatorHealthGroups() throws IOException {
        // Cross-cutting: NOT ONE of the app Deployments may ship without both actuator health-group
        // probes — this is the invariant that makes the k8s rollout gate on real app health.
        List<Map<String, Object>> docs = loadManifests();
        Map<String, String> readiness = new HashMap<>();
        Map<String, String> liveness = new HashMap<>();
        for (Map<String, Object> doc : docs) {
            if (!"Deployment".equals(kind(doc))) continue;
            String n = name(doc);
            if (!APP_PORTS.containsKey(n)) continue;   // skip infra deployments (probed via exec/tcp)
            Map<String, Object> container = firstContainer(doc);
            @SuppressWarnings("unchecked")
            Map<String, Object> rp = (Map<String, Object>) container.get("readinessProbe");
            @SuppressWarnings("unchecked")
            Map<String, Object> lp = (Map<String, Object>) container.get("livenessProbe");
            assertNotNull(rp, n + " has no readinessProbe");
            assertNotNull(lp, n + " has no livenessProbe");
            readiness.put(n, String.valueOf(((Map<?, ?>) rp.get("httpGet")).get("path")));
            liveness.put(n, String.valueOf(((Map<?, ?>) lp.get("httpGet")).get("path")));
        }
        assertEquals(APP_PORTS.keySet(), readiness.keySet(), "not every app Deployment has a readiness probe");
        assertTrue(readiness.values().stream().allMatch(READINESS_PATH::equals),
                "a readiness probe does not target " + READINESS_PATH + ": " + readiness);
        assertTrue(liveness.values().stream().allMatch(LIVENESS_PATH::equals),
                "a liveness probe does not target " + LIVENESS_PATH + ": " + liveness);
    }
}
