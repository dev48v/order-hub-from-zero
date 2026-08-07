package dev.dev48v.orderhub.deploy;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Day 46 (Phase 6 — DEPLOY) — ZERO-DOWNTIME DEPLOY guard.
 *
 * <p>The Day-46 sibling of {@link KubernetesManifestTest} / {@link HelmChartTest} / {@link CiWorkflowTest}:
 * a plain JUnit + SnakeYAML test that needs NO live cluster and applies nothing. Where Day 43 proved every
 * app has a Deployment + probes, Day 46 proves those Deployments are wired so a rollout NEVER drops a
 * request. Three things together make that true, and this test asserts all three so a future edit that
 * quietly weakens one (bumps {@code maxUnavailable} off 0, deletes the {@code preStop} drain, or turns off
 * graceful shutdown) turns the reactor red instead of surfacing as dropped traffic in production.
 *
 * <p>For every one of the eight app Deployments under {@code k8s/} it verifies:
 * <ol>
 *   <li><b>RollingUpdate, maxUnavailable: 0</b> — the strategy never lets the running replica count dip
 *       below desired during a roll (a new pod must go Ready before an old one is retired), and
 *       {@code maxSurge} is a positive value so a fresh pod can be added first — the mechanism that lets
 *       even a single-replica service roll over with no gap;</li>
 *   <li><b>drain wiring</b> — a container {@code lifecycle.preStop} exec hook that {@code sleep}s (so the
 *       pod leaves the Service endpoints before it gets SIGTERM) AND a pod-level
 *       {@code terminationGracePeriodSeconds} big enough to cover that sleep plus the app's graceful
 *       shutdown (so in-flight requests finish before a SIGKILL);</li>
 *   <li><b>revision history</b> — a {@code revisionHistoryLimit} is kept so {@code kubectl rollout undo}
 *       has a previous ReplicaSet to roll back to.</li>
 * </ol>
 *
 * <p>And for every service's {@code application.yml} it verifies the <b>k8s-profile document enables
 * graceful shutdown</b> ({@code server.shutdown: graceful} + {@code spring.lifecycle.timeout-per-shutdown-phase})
 * — gated behind the {@code k8s} profile, so local / test / compose behaviour is byte-for-byte unchanged.
 */
class ZeroDowntimeDeployTest {

    /** The eight runnable Spring Boot modules (same set the Day-43/44/45 deploy tests assert). */
    private static final List<String> APP_MODULES = List.of(
            "config-server", "eureka-server", "api-gateway", "order-service",
            "inventory-service", "payment-service", "shipping-service", "notification-service");

    // ── locate the repo root (same walk-up as the Day-42/43 tests) ──────────────────────────
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

    /** Parse every k8s/*.yaml file into a flat list of YAML documents. */
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

    private static Map<String, Object> appDeployment(List<Map<String, Object>> docs, String app) {
        return docs.stream()
                .filter(d -> "Deployment".equals(kind(d)) && app.equals(name(d)))
                .findFirst()
                .orElseGet(() -> fail("missing Deployment for app '" + app + "'"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object node, String what) {
        assertTrue(node instanceof Map, "expected a mapping for " + what + ", was: " + node);
        return (Map<String, Object>) node;
    }

    /** spec.template.spec — the pod spec. */
    private static Map<String, Object> podSpec(Map<String, Object> deployment) {
        Map<String, Object> spec = map(deployment.get("spec"), "deployment.spec");
        Map<String, Object> template = map(spec.get("template"), "spec.template");
        return map(template.get("spec"), "spec.template.spec");
    }

    /** spec.template.spec.containers[0] — the app container. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstContainer(Map<String, Object> deployment) {
        List<Object> containers = (List<Object>) podSpec(deployment).get("containers");
        assertTrue(containers != null && !containers.isEmpty(), "deployment has no containers");
        return (Map<String, Object>) containers.get(0);
    }

    // ── 1. RollingUpdate with maxUnavailable: 0 (never drop below desired) ───────────────────
    @Test
    void everyAppDeploymentRollsWithMaxUnavailableZero() throws IOException {
        List<Map<String, Object>> docs = loadManifests();
        for (String app : APP_MODULES) {
            Map<String, Object> spec = map(appDeployment(docs, app).get("spec"), app + " .spec");

            Map<String, Object> strategy = map(spec.get("strategy"), app + " .spec.strategy");
            assertEquals("RollingUpdate", String.valueOf(strategy.get("type")),
                    app + " must use a RollingUpdate strategy");

            Map<String, Object> rolling = map(strategy.get("rollingUpdate"), app + " .strategy.rollingUpdate");
            assertEquals("0", String.valueOf(rolling.get("maxUnavailable")),
                    app + " must set maxUnavailable: 0 so the roll never drops below the desired replica count");

            Object maxSurge = rolling.get("maxSurge");
            assertNotNull(maxSurge, app + " must set a maxSurge so a new pod can be added before an old one is retired");
            assertTrue(isPositive(maxSurge),
                    app + " maxSurge must be > 0 (with maxUnavailable:0, a zero surge would deadlock the roll), was " + maxSurge);

            // revisionHistoryLimit — kept so `kubectl rollout undo` has a previous ReplicaSet.
            Object revisionHistory = spec.get("revisionHistoryLimit");
            assertNotNull(revisionHistory, app + " must set revisionHistoryLimit (needed for kubectl rollout undo)");
            assertTrue(((Number) revisionHistory).intValue() > 0,
                    app + " revisionHistoryLimit must be > 0, was " + revisionHistory);
        }
    }

    // ── 2. preStop drain hook + terminationGracePeriodSeconds (finish in-flight requests) ────
    @Test
    @SuppressWarnings("unchecked")
    void everyAppDeploymentDrainsBeforeTermination() throws IOException {
        List<Map<String, Object>> docs = loadManifests();
        for (String app : APP_MODULES) {
            Map<String, Object> deployment = appDeployment(docs, app);

            // Pod-level terminationGracePeriodSeconds — the total SIGTERM→SIGKILL budget.
            Object graceObj = podSpec(deployment).get("terminationGracePeriodSeconds");
            assertNotNull(graceObj, app + " must set terminationGracePeriodSeconds so drain + graceful shutdown fit before SIGKILL");
            int grace = ((Number) graceObj).intValue();
            assertTrue(grace > 0, app + " terminationGracePeriodSeconds must be > 0, was " + grace);

            // Container-level lifecycle.preStop exec hook that sleeps.
            Map<String, Object> container = firstContainer(deployment);
            Map<String, Object> lifecycle = map(container.get("lifecycle"), app + " container.lifecycle");
            Map<String, Object> preStop = map(lifecycle.get("preStop"), app + " lifecycle.preStop");
            Map<String, Object> exec = map(preStop.get("exec"), app + " preStop.exec");
            List<Object> command = (List<Object>) exec.get("command");
            assertTrue(command != null && !command.isEmpty(), app + " preStop.exec has no command");
            String joined = command.stream().map(String::valueOf).reduce("", (a, b) -> a + " " + b);
            assertTrue(joined.contains("sleep"),
                    app + " preStop hook must sleep to let endpoint removal propagate before SIGTERM, was " + joined);

            int sleepSeconds = firstIntIn(joined);
            assertTrue(sleepSeconds > 0, app + " preStop sleep must be a positive number of seconds, was " + joined);
            // Coherence: the total grace budget must exceed the preStop sleep, or the app would be
            // SIGKILLed before it ever gets a chance to shut down gracefully.
            assertTrue(grace > sleepSeconds,
                    app + " terminationGracePeriodSeconds (" + grace + ") must exceed the preStop sleep ("
                            + sleepSeconds + ") so graceful shutdown has time to run");
        }
    }

    // ── 3. The k8s-profile config enables Spring Boot graceful shutdown ──────────────────────
    @Test
    void everyServiceK8sProfileEnablesGracefulShutdown() throws IOException {
        Yaml yaml = new Yaml();
        for (String svc : APP_MODULES) {
            Path appYml = repoRoot().resolve(svc).resolve("src/main/resources/application.yml");
            assertTrue(Files.exists(appYml), "missing application.yml for " + svc);

            Map<String, Object> k8sDoc = null;
            try (Reader r = Files.newBufferedReader(appYml, StandardCharsets.UTF_8)) {
                for (Object o : yaml.loadAll(r)) {
                    if (!(o instanceof Map)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> doc = (Map<String, Object>) o;
                    if ("k8s".equals(onProfile(doc))) {
                        k8sDoc = doc;
                    }
                }
            }
            assertNotNull(k8sDoc, svc + " has no k8s-profile document in application.yml");

            // server.shutdown: graceful
            Map<String, Object> server = map(k8sDoc.get("server"), svc + " k8s-doc.server");
            assertEquals("graceful", String.valueOf(server.get("shutdown")),
                    svc + " k8s profile must set server.shutdown=graceful");

            // spring.lifecycle.timeout-per-shutdown-phase
            Map<String, Object> spring = map(k8sDoc.get("spring"), svc + " k8s-doc.spring");
            Map<String, Object> lifecycle = map(spring.get("lifecycle"), svc + " spring.lifecycle");
            Object timeout = lifecycle.get("timeout-per-shutdown-phase");
            assertNotNull(timeout,
                    svc + " k8s profile must set spring.lifecycle.timeout-per-shutdown-phase so in-flight requests get a bounded drain window");
            assertTrue(!String.valueOf(timeout).isBlank(), svc + " timeout-per-shutdown-phase is blank");
        }
    }

    /** spring.config.activate.on-profile of a document, or null if it isn't a profile-gated doc. */
    @SuppressWarnings("unchecked")
    private static String onProfile(Map<String, Object> doc) {
        Object spring = doc.get("spring");
        if (!(spring instanceof Map)) return null;
        Object config = ((Map<String, Object>) spring).get("config");
        if (!(config instanceof Map)) return null;
        Object activate = ((Map<String, Object>) config).get("activate");
        if (!(activate instanceof Map)) return null;
        Object onProfile = ((Map<String, Object>) activate).get("on-profile");
        return onProfile == null ? null : String.valueOf(onProfile);
    }

    /** True if a YAML scalar (Integer, or a String like "1" / "25%") represents a value strictly greater than zero. */
    private static boolean isPositive(Object v) {
        if (v instanceof Number) return ((Number) v).doubleValue() > 0;
        String s = String.valueOf(v).trim().replace("%", "");
        try {
            return Double.parseDouble(s) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** The first run of digits in a string (e.g. the seconds out of "sh -c sleep 10"), or -1 if none. */
    private static int firstIntIn(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(s);
        return m.find() ? Integer.parseInt(m.group()) : -1;
    }
}
