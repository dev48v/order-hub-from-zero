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
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Day 47 (Phase 6 — DEPLOY) — k6 LOAD-TEST guard.
 *
 * <p>The Day-47 sibling of {@link CiWorkflowTest} / {@link ZeroDowntimeDeployTest}: a plain JUnit test that
 * needs NO k6 binary and starts nothing. Day 45 made {@code git push} build + test the reactor and Day 46
 * made the rollout drop-free; Day 47 adds a Grafana k6 load-test suite under {@code k6/} plus a CI job that
 * runs it and FAILS the build if a Service-Level Objective (SLO) threshold is breached. There is no way (and
 * no need) to spin up VUs on this unit build, so — exactly as the Day-42..46 deploy tests do for
 * Docker/k8s/helm/CI — this test STATICALLY validates the artifacts: it reads {@code k6/order-load.js} as
 * text and parses {@code .github/workflows/ci.yml} with SnakeYAML, and asserts the suite is wired the way it
 * must be. If someone deletes the staged ramp, drops a threshold, stops exporting {@code handleSummary},
 * points the script off the real order endpoints, or removes the k6 CI job, the reactor build goes red.
 *
 * <p>Specifically it verifies the SCRIPT:
 * <ol>
 *   <li>declares {@code options.stages} (a VU ramp) and {@code options.thresholds};</li>
 *   <li>gates on {@code http_req_duration} p95 AND {@code http_req_failed} rate, plus a custom
 *       {@code Trend}/{@code Rate} metric;</li>
 *   <li>exports both a {@code default} function and {@code handleSummary} (writing a JSON report);</li>
 *   <li>drives the REAL order API — {@code POST}/{@code GET}/list against {@code /api/orders} — with a
 *       parameterized {@code __ENV.BASE_URL} and {@code check()}s;</li>
 * </ol>
 * and the CI WORKFLOW has a k6 <b>load-test job</b> that {@code needs: build-test} and actually runs
 * {@code k6 run k6/order-load.js}.
 */
class K6LoadTestTest {

    // ── locate the repo root by walking up until we find k6/order-load.js + the CI workflow ──────
    private static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null) {
            if (Files.exists(dir.resolve("k6").resolve("order-load.js"))
                    && Files.exists(dir.resolve(".github").resolve("workflows").resolve("ci.yml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return fail("Could not locate repo root (no k6/order-load.js + .github/workflows/ci.yml) walking up from "
                + System.getProperty("user.dir"));
    }

    private static String loadScript() throws IOException {
        return Files.readString(repoRoot().resolve("k6").resolve("order-load.js"), StandardCharsets.UTF_8);
    }

    /** Case-insensitive "does this text contain a match for the regex" helper. */
    private static boolean matches(String haystack, String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(haystack).find();
    }

    // ── 1. the load PROFILE (stages) + the pass/fail GATES (thresholds) ──────────────────────────
    @Test
    void scriptDefinesStagedRampAndThresholds() throws IOException {
        String js = loadScript();

        assertTrue(matches(js, "export\\s+const\\s+options"),
                "order-load.js must export an options object");
        assertTrue(matches(js, "stages\\s*:\\s*\\["),
                "order-load.js options must define a 'stages' VU ramp (ramp-up → steady → ramp-down)");
        // at least a couple of stages with target VUs
        assertTrue(matches(js, "target\\s*:\\s*\\d+.*target\\s*:\\s*\\d+"),
                "the stages array must ramp VUs via multiple { duration, target } entries");
        assertTrue(matches(js, "thresholds\\s*:\\s*\\{"),
                "order-load.js options must define a 'thresholds' block (the pass/fail SLO gates)");
    }

    // ── 2. the two headline SLOs (latency p95 + error rate) + a custom metric ────────────────────
    @Test
    void thresholdsCoverP95DurationAndFailureRatePlusACustomMetric() throws IOException {
        String js = loadScript();

        // p95 latency gate on the built-in http_req_duration.
        assertTrue(matches(js, "http_req_duration"),
                "thresholds must include http_req_duration");
        assertTrue(matches(js, "p\\(95\\)\\s*<"),
                "thresholds must gate the p95 of http_req_duration (p(95)<…) — the tail users actually feel");

        // error-rate gate on the built-in http_req_failed.
        assertTrue(matches(js, "http_req_failed"),
                "thresholds must include http_req_failed (the availability SLO)");
        assertTrue(matches(js, "http_req_failed[^\\n]*rate\\s*<"),
                "http_req_failed must be gated with a rate<… threshold");

        // a CUSTOM metric — a Trend or a Rate — imported from k6/metrics and constructed.
        assertTrue(matches(js, "from\\s+['\"]k6/metrics['\"]"),
                "order-load.js must import custom metric types from 'k6/metrics'");
        assertTrue(matches(js, "new\\s+(Trend|Rate)\\s*\\("),
                "order-load.js must define a custom Trend or Rate metric (e.g. write-path latency / flow success)");
    }

    // ── 3. exports: a default VU function + a handleSummary that writes a JSON report ────────────
    @Test
    void scriptExportsDefaultFunctionAndHandleSummary() throws IOException {
        String js = loadScript();

        assertTrue(matches(js, "export\\s+default\\s+function"),
                "order-load.js must export a default function (the per-VU scenario)");
        assertTrue(matches(js, "export\\s+function\\s+handleSummary|handleSummary\\s*[:=]"),
                "order-load.js must export handleSummary to emit an end-of-run report");
        assertTrue(matches(js, "\\.json"),
                "handleSummary should write a JSON report (a .json artifact)");
    }

    // ── 4. it drives the REAL order API (create → get → list) with a parameterized BASE_URL ──────
    @Test
    void scriptHitsRealOrderEndpointsWithParameterizedBaseUrl() throws IOException {
        String js = loadScript();

        // Parameterized target — never a hard-coded host baked in with no override.
        assertTrue(matches(js, "__ENV\\.BASE_URL"),
                "order-load.js must read BASE_URL from the environment (__ENV.BASE_URL) so the target is parameterized");

        // The real order endpoint the OrderController serves.
        assertTrue(matches(js, "/api/orders"),
                "order-load.js must hit the real /api/orders endpoints");

        // create → POST, get/list → GET.
        assertTrue(matches(js, "http\\.post\\s*\\("),
                "order-load.js must POST to create an order");
        assertTrue(matches(js, "http\\.get\\s*\\("),
                "order-load.js must GET to fetch / list orders");

        // assertions on the responses.
        assertTrue(matches(js, "check\\s*\\("),
                "order-load.js must check() response status/body");
        assertTrue(matches(js, "201"),
                "order-load.js must assert the 201 Created on the order create");
    }

    // ── 5. the CI workflow has a k6 load-test job that needs build-test and runs the script ──────
    @Test
    @SuppressWarnings("unchecked")
    void ciWorkflowHasK6LoadTestJob() throws IOException {
        Path ci = repoRoot().resolve(".github").resolve("workflows").resolve("ci.yml");
        Map<String, Object> wf;
        try (Reader r = Files.newBufferedReader(ci, StandardCharsets.UTF_8)) {
            Object o = new Yaml().load(r);
            assertTrue(o instanceof Map, "ci.yml is not a YAML mapping");
            wf = (Map<String, Object>) o;
        }

        Object jobsNode = wf.get("jobs");
        assertTrue(jobsNode instanceof Map, "ci.yml has no 'jobs' mapping");
        Map<String, Object> jobs = (Map<String, Object>) jobsNode;

        // Find THE job whose steps use grafana/setup-k6-action or run `k6 run`.
        Map<String, Object> k6Job = null;
        boolean runsLoadScript = false;
        for (Object jobObj : jobs.values()) {
            if (!(jobObj instanceof Map)) continue;
            Map<String, Object> job = (Map<String, Object>) jobObj;
            Object stepsNode = job.get("steps");
            if (!(stepsNode instanceof List)) continue;
            boolean isK6 = false;
            for (Object stepObj : (List<Object>) stepsNode) {
                if (!(stepObj instanceof Map)) continue;
                Map<String, Object> step = (Map<String, Object>) stepObj;
                String uses = String.valueOf(step.get("uses"));
                String run = String.valueOf(step.get("run"));
                if (uses.startsWith("grafana/setup-k6-action")) {
                    isK6 = true;
                }
                if (run.contains("k6 run")) {
                    isK6 = true;
                }
                if (run.contains("k6 run") && run.contains("k6/order-load.js")) {
                    runsLoadScript = true;
                }
            }
            if (isK6) {
                k6Job = job;
            }
        }

        assertNotNull(k6Job, "ci.yml must define a k6 load-test job (uses grafana/setup-k6-action and/or runs `k6 run`)");

        // It must only load-test a green reactor.
        Object needs = k6Job.get("needs");
        assertNotNull(needs, "the k6 load-test job must declare 'needs' (only load-test a green reactor)");
        boolean needsBuildTest = (needs instanceof List)
                ? ((List<?>) needs).stream().map(String::valueOf).anyMatch("build-test"::equals)
                : "build-test".equals(String.valueOf(needs));
        assertTrue(needsBuildTest, "the k6 load-test job must `needs: build-test`");

        // And it must actually run the load script (so a breached threshold fails the build).
        assertTrue(runsLoadScript, "the k6 load-test job must run `k6 run k6/order-load.js`");
    }
}
