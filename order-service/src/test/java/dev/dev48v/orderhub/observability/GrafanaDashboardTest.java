package dev.dev48v.orderhub.observability;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// Day 38 — GRAFANA dashboards (Phase 5 · observability). Grafana itself is infrastructure (a container,
// provisioned from files), so there is no Java code to unit-test in the usual sense. What CAN and SHOULD
// be guarded is the deliverable that is easy to break silently: the dashboard DEFINITION. This is a plain
// JUnit test (no Spring context — fast, and it does not touch the shared application context, so all 126
// prior tests are unaffected) that reads grafana/dashboards/orderhub.json off disk and asserts:
//   1) it is well-formed JSON with a valid Grafana dashboard shape (title, uid, schemaVersion, panels[]);
//   2) every panel carries at least one PromQL target, wired to the provisioned Prometheus datasource uid;
//   3) the PromQL across the dashboard actually references the Day-37 meter names the panels claim to plot
//      (orders_placed_total, order_processing_seconds, orders_open) and uses rate()/histogram_quantile();
//   4) the specific RED/USE panels we shipped are present by title.
// If someone renames a meter in OrderMetrics or fat-fingers a query, this test fails at build time rather
// than the panel silently going blank in Grafana at 3am.
@DisplayName("Day 38 · Grafana dashboard JSON is well-formed and plots the Day-37 meters")
class GrafanaDashboardTest {

    private static JsonNode dashboard;
    private static List<String> allExpr;

    @BeforeAll
    static void loadDashboard() throws IOException {
        Path json = locateDashboard();
        assertThat(Files.exists(json))
                .as("grafana/dashboards/orderhub.json must exist at %s", json)
                .isTrue();
        dashboard = new ObjectMapper().readTree(Files.readAllBytes(json));

        // Flatten every panel target's PromQL expression once, for the query-content assertions below.
        allExpr = new ArrayList<>();
        for (JsonNode panel : dashboard.path("panels")) {
            for (JsonNode target : panel.path("targets")) {
                String expr = target.path("expr").asText("");
                if (!expr.isBlank()) {
                    allExpr.add(expr);
                }
            }
        }
    }

    // Walk up from the module's working directory to find the repo-root dashboard JSON, so the test works
    // whether Surefire runs it from the order-service module dir or the reactor root.
    private static Path locateDashboard() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve("grafana").resolve("dashboards").resolve("orderhub.json");
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        // Fall back to the conventional relative path so the assertion in loadDashboard() reports it clearly.
        return Paths.get(System.getProperty("user.dir"))
                .resolve("..").resolve("grafana").resolve("dashboards").resolve("orderhub.json")
                .normalize();
    }

    @Test
    @DisplayName("has a valid Grafana dashboard envelope (title, uid, schemaVersion, non-empty panels)")
    void validDashboardEnvelope() {
        assertThat(dashboard.path("title").asText()).isEqualTo("OrderHub — Orders Overview");
        assertThat(dashboard.path("uid").asText()).isNotBlank();
        assertThat(dashboard.path("schemaVersion").isInt())
                .as("schemaVersion is a number")
                .isTrue();
        assertThat(dashboard.path("schemaVersion").asInt()).isGreaterThanOrEqualTo(30);
        JsonNode panels = dashboard.path("panels");
        assertThat(panels.isArray()).as("panels is an array").isTrue();
        assertThat(panels).as("dashboard has several panels").hasSizeGreaterThanOrEqualTo(6);
    }

    @Test
    @DisplayName("every panel has a title, a gridPos, and at least one PromQL target on the Prometheus datasource")
    void everyPanelIsWellFormed() {
        for (JsonNode panel : dashboard.path("panels")) {
            String title = panel.path("title").asText("");
            assertThat(title).as("panel has a title").isNotBlank();
            assertThat(panel.has("gridPos")).as("panel '%s' has gridPos", title).isTrue();
            assertThat(panel.path("targets").isArray() && panel.path("targets").size() >= 1)
                    .as("panel '%s' has at least one target", title).isTrue();
            for (JsonNode target : panel.path("targets")) {
                assertThat(target.path("expr").asText())
                        .as("panel '%s' target has a PromQL expr", title).isNotBlank();
                assertThat(target.path("datasource").path("uid").asText())
                        .as("panel '%s' target points at the provisioned Prometheus datasource", title)
                        .isEqualTo("orderhub-prometheus");
            }
        }
    }

    @Test
    @DisplayName("PromQL references the Day-37 custom meter names")
    void referencesDay37Meters() {
        String joined = String.join("\n", allExpr);
        assertThat(joined)
                .as("Counter -> orders_placed_total is plotted")
                .contains("orders_placed_total");
        assertThat(joined)
                .as("Timer -> order_processing_seconds (histogram buckets) is plotted")
                .contains("order_processing_seconds");
        assertThat(joined)
                .as("Gauge -> orders_open is plotted")
                .contains("orders_open");
    }

    @Test
    @DisplayName("PromQL uses rate() for the counter and histogram_quantile() for the latency percentiles")
    void usesRateAndHistogramQuantile() {
        String joined = String.join("\n", allExpr);
        assertThat(joined)
                .as("counter is turned into a rate")
                .contains("rate(orders_placed_total");
        assertThat(joined)
                .as("latency percentiles use histogram_quantile over the timer buckets")
                .contains("histogram_quantile")
                .contains("order_processing_seconds_bucket");
        // The three latency percentiles we ship.
        assertThat(joined).contains("histogram_quantile(0.50");
        assertThat(joined).contains("histogram_quantile(0.95");
        assertThat(joined).contains("histogram_quantile(0.99");
    }

    @Test
    @DisplayName("the expected RED/USE panels are present by title")
    void expectedPanelsPresent() {
        List<String> titles = new ArrayList<>();
        for (JsonNode panel : dashboard.path("panels")) {
            titles.add(panel.path("title").asText().toLowerCase());
        }
        assertThat(titles).anyMatch(t -> t.contains("orders placed"));      // Rate + Errors
        assertThat(titles).anyMatch(t -> t.contains("latency"));            // Duration (p50/p95/p99)
        assertThat(titles).anyMatch(t -> t.contains("open orders"));        // Saturation gauge
        assertThat(titles).anyMatch(t -> t.contains("error ratio"));        // Errors SLO
    }
}
