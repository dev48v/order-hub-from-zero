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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Day 45 (Phase 6 — DEPLOY) — CI/CD PIPELINE guard.
 *
 * <p>The Day-45 sibling of {@link HelmChartTest} / {@link KubernetesManifestTest}. Where Day 44
 * templatized the manifests into a Helm chart, Day 45 adds a GitHub Actions pipeline under
 * {@code .github/workflows/}. There is no way (and no need) to run GitHub Actions on this build box,
 * so — exactly as the Day-42/43/44 deploy tests do for Docker/k8s/helm — this test parses the
 * workflow's PLAIN YAML with SnakeYAML and asserts the pipeline is wired the way it should be. If
 * someone edits {@code ci.yml} and breaks a trigger, drops the JDK-21 cache, decouples the image
 * build from the test gate, or (worst) pastes a real secret in, the reactor build goes red.
 *
 * <p>Specifically it verifies:
 * <ol>
 *   <li>the workflow triggers on {@code push} + {@code pull_request} to master and
 *       {@code workflow_dispatch};</li>
 *   <li>a {@code build-test} job checks out, sets up <b>Temurin JDK 21 with Maven caching</b>, and
 *       runs {@code mvn … verify};</li>
 *   <li>the {@code docker-build} job {@code needs} {@code build-test} (so images are only built on a
 *       green reactor) and matrixes over the EIGHT service modules;</li>
 *   <li>no hard-coded secret literals appear — every {@code secrets.*} reference is inside a
 *       {@code ${{ … }}} expression, never a pasted value.</li>
 * </ol>
 *
 * <p>Note on parsing: GitHub's trigger key is {@code on:}, which the YAML 1.1 resolver SnakeYAML
 * uses reads as the boolean {@code true}. The workflow keeps the idiomatic unquoted {@code on:}; the
 * test simply looks the triggers node up under either the {@code "on"} string or {@link Boolean#TRUE}.
 */
class CiWorkflowTest {

    /** The eight runnable modules the docker-build matrix must cover (same set as the helm/k8s tests). */
    private static final List<String> APP_MODULES = List.of(
            "config-server", "eureka-server", "api-gateway", "order-service",
            "inventory-service", "payment-service", "shipping-service", "notification-service");

    // ── locate .github/workflows/ci.yml by walking up from the module's working dir ─────────
    private static Path workflowFile() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            Path wf = dir.resolve(".github").resolve("workflows").resolve("ci.yml");
            if (Files.exists(wf)) {
                return wf;
            }
            dir = dir.getParent();
        }
        return fail("Could not locate .github/workflows/ci.yml walking up from "
                + System.getProperty("user.dir"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadWorkflow() throws IOException {
        try (Reader r = Files.newBufferedReader(workflowFile(), StandardCharsets.UTF_8)) {
            Object o = new Yaml().load(r);   // throws on malformed YAML
            assertTrue(o instanceof Map, "ci.yml is not a YAML mapping");
            return (Map<String, Object>) o;
        }
    }

    private static String rawWorkflow() throws IOException {
        return Files.readString(workflowFile(), StandardCharsets.UTF_8);
    }

    /** The {@code on:} node — under the string key "on", or (SnakeYAML YAML-1.1 quirk) Boolean.TRUE. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> triggers(Map<String, Object> wf) {
        Object node = wf.containsKey("on") ? wf.get("on") : wf.get(Boolean.TRUE);
        assertTrue(node instanceof Map, "ci.yml has no 'on:' triggers mapping");
        return (Map<String, Object>) node;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> job(Map<String, Object> wf, String name) {
        Object jobsNode = wf.get("jobs");
        assertTrue(jobsNode instanceof Map, "ci.yml has no 'jobs' mapping");
        Object jobNode = ((Map<String, Object>) jobsNode).get(name);
        assertTrue(jobNode instanceof Map, "ci.yml has no '" + name + "' job");
        return (Map<String, Object>) jobNode;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> steps(Map<String, Object> job) {
        Object stepsNode = job.get("steps");
        assertTrue(stepsNode instanceof List, "job has no 'steps' list");
        return (List<Map<String, Object>>) (List<?>) stepsNode;
    }

    @Test
    void workflowTriggersOnPushPullRequestAndDispatch() throws IOException {
        Map<String, Object> wf = loadWorkflow();
        assertEquals("CI", String.valueOf(wf.get("name")), "workflow should be named 'CI'");

        Map<String, Object> on = triggers(wf);
        assertTrue(on.containsKey("push"), "workflow must trigger on push");
        assertTrue(on.containsKey("pull_request"), "workflow must trigger on pull_request");
        assertTrue(on.containsKey("workflow_dispatch"), "workflow must allow manual workflow_dispatch");

        // push must be gated to the master branch.
        assertTrue(String.valueOf(on.get("push")).contains("master"),
                "push trigger must target the master branch");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildTestJobRunsMavenVerifyOnJdk21WithMavenCache() throws IOException {
        Map<String, Object> wf = loadWorkflow();
        Map<String, Object> buildTest = job(wf, "build-test");

        boolean checksOut = false;
        boolean setsUpJdk21WithCache = false;
        boolean runsMavenVerify = false;

        for (Map<String, Object> step : steps(buildTest)) {
            Object uses = step.get("uses");
            Object run = step.get("run");

            if (uses != null && String.valueOf(uses).startsWith("actions/checkout")) {
                checksOut = true;
            }
            if (uses != null && String.valueOf(uses).startsWith("actions/setup-java")) {
                Object withNode = step.get("with");
                assertTrue(withNode instanceof Map, "setup-java step has no 'with' block");
                Map<String, Object> with = (Map<String, Object>) withNode;
                boolean jdk21 = "21".equals(String.valueOf(with.get("java-version")));
                boolean temurin = "temurin".equals(String.valueOf(with.get("distribution")));
                boolean mavenCache = "maven".equals(String.valueOf(with.get("cache")));
                if (jdk21 && temurin && mavenCache) {
                    setsUpJdk21WithCache = true;
                }
            }
            if (run != null) {
                String cmd = String.valueOf(run);
                if (cmd.contains("mvn") && cmd.contains("verify")) {
                    runsMavenVerify = true;
                }
            }
        }

        assertTrue(checksOut, "build-test must check out the repo (actions/checkout)");
        assertTrue(setsUpJdk21WithCache,
                "build-test must set up Temurin JDK 21 with Maven caching (actions/setup-java, "
                        + "distribution: temurin, java-version: 21, cache: maven)");
        assertTrue(runsMavenVerify, "build-test must run `mvn … verify`");
    }

    @Test
    void dockerBuildDependsOnBuildTestAndMatrixesTheEightModules() throws IOException {
        Map<String, Object> wf = loadWorkflow();
        Map<String, Object> dockerBuild = job(wf, "docker-build");

        // needs: build-test — accept either the scalar or list form.
        Object needs = dockerBuild.get("needs");
        assertNotNull(needs, "docker-build must declare 'needs'");
        boolean dependsOnBuildTest = (needs instanceof List)
                ? ((List<?>) needs).stream().map(String::valueOf).anyMatch("build-test"::equals)
                : "build-test".equals(String.valueOf(needs));
        assertTrue(dependsOnBuildTest, "docker-build must `needs: build-test` (only build images on a green reactor)");

        // strategy.matrix.<key> must list exactly the eight service modules.
        List<?> modules = matrixModules(dockerBuild);
        assertNotNull(modules, "docker-build must define a strategy matrix over the service modules");
        for (String module : APP_MODULES) {
            assertTrue(modules.stream().map(String::valueOf).anyMatch(module::equals),
                    "docker-build matrix is missing module '" + module + "'");
        }
        assertEquals(APP_MODULES.size(), modules.size(),
                "docker-build matrix should cover exactly the eight service modules");
    }

    @SuppressWarnings("unchecked")
    private static List<?> matrixModules(Map<String, Object> job) {
        Object strategy = job.get("strategy");
        assertTrue(strategy instanceof Map, "docker-build has no 'strategy' block");
        Object matrix = ((Map<String, Object>) strategy).get("matrix");
        assertTrue(matrix instanceof Map, "docker-build strategy has no 'matrix'");
        // The single dimension over the modules (whatever it's named — e.g. 'module').
        for (Object v : ((Map<String, Object>) matrix).values()) {
            if (v instanceof List) {
                return (List<?>) v;
            }
        }
        return fail("docker-build matrix has no list dimension to fan out over");
    }

    @Test
    void noHardCodedSecretsOnlyExpressionReferences() throws IOException {
        String raw = rawWorkflow();

        // Every `secrets.NAME` must sit INSIDE an open ${{ … }} expression — never a pasted literal.
        Matcher m = Pattern.compile("secrets\\.[A-Za-z_][A-Za-z0-9_]*").matcher(raw);
        while (m.find()) {
            int idx = m.start();
            int lastOpen = raw.lastIndexOf("${{", idx);
            int lastClose = raw.lastIndexOf("}}", idx);
            assertTrue(lastOpen > lastClose,
                    "secrets reference '" + m.group() + "' is not inside a ${{ … }} expression — "
                            + "credentials must never be hard-coded, only referenced via ${{ secrets.* }}");
        }

        // Defence-in-depth: no key literally assigns a password/token to a bare (non-expression) value.
        Matcher creds = Pattern.compile("(?im)^\\s*(password|token|secret)\\s*:\\s*(\\S+)").matcher(raw);
        while (creds.find()) {
            // Skip commented lines.
            int lineStart = raw.lastIndexOf('\n', creds.start()) + 1;
            if (raw.substring(lineStart, creds.start()).trim().startsWith("#")
                    || raw.charAt(lineStart) == '#') {
                continue;
            }
            String value = creds.group(2);
            assertTrue(value.startsWith("${{") || value.startsWith("\"${{") || value.startsWith("'${{"),
                    "credential key '" + creds.group(1) + "' assigns a hard-coded value '" + value
                            + "' — it must be a ${{ secrets.* }} reference");
        }
    }
}
