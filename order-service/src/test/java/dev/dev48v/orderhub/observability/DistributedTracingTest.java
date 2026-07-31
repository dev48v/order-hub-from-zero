package dev.dev48v.orderhub.observability;

import brave.baggage.BaggageField;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Day 39 — DISTRIBUTED TRACING + LOKI (Phase 5 · observability, the third pillar). This test guards the parts
// that are easy to break silently, WITHOUT booting the full app or turning tracing on for anyone else:
//
//   1) the GATE — TracingConfig's beans exist only when orderhub.observability.tracing.enabled=true. Proven with
//      an ApplicationContextRunner (a sliced, throwaway context — it does NOT touch the shared @SpringBootTest
//      context, so all 131 prior tests are unaffected and stay OFF-by-default). This is the "tracing config
//      beans load when enabled" assertion the day calls for.
//   2) the LOG correlation — logback-spring.xml is well-formed and its console pattern carries the trace-id +
//      span-id from the MDC, and its Loki appender (gated by the `loki` profile) writes the traceId too. A plain
//      classpath read + XML parse; no Spring context.
//   3) the LOGS↔TRACES wiring — the provisioned Grafana Loki datasource is well-formed YAML, points at the
//      compose `loki` service, and its derived field pulls the traceId out of the log line to link to the trace.
//
// If someone deletes the MDC fields from the log pattern, drops the Loki appender, or breaks the datasource
// YAML, this fails at build time rather than the correlation silently going dark in production.
@DisplayName("Day 39 · distributed tracing gate + Loki/log correlation wiring")
class DistributedTracingTest {

    // A sliced context loading ONLY TracingConfig — fast, isolated, and it evaluates the @ConditionalOnProperty
    // gate exactly as the real app would, without any of the app's other beans.
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(TracingConfig.class);

    @Test
    @DisplayName("TracingConfig beans load when orderhub.observability.tracing.enabled=true")
    void tracingBeansLoadWhenEnabled() {
        runner.withPropertyValues("orderhub.observability.tracing.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    // the orderId baggage field is created and named as declared
                    assertThat(ctx).hasSingleBean(BaggageField.class);
                    assertThat(ctx.getBean(BaggageField.class).name())
                            .isEqualTo(TracingConfig.ORDER_ID_FIELD);
                });
    }

    @Test
    @DisplayName("TracingConfig is inert by default (flag absent) — no tracing beans, prior behaviour unchanged")
    void tracingBeansAbsentWhenDisabled() {
        // No property set → @ConditionalOnProperty(havingValue=true, matchIfMissing=false) does not match.
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(BaggageField.class);
            assertThat(ctx).doesNotHaveBean(TracingConfig.class);
        });
        // And explicitly false stays off.
        runner.withPropertyValues("orderhub.observability.tracing.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(BaggageField.class));
    }

    @Test
    @DisplayName("logback-spring.xml is well-formed and carries trace-id/span-id in MDC + a gated Loki appender")
    void logbackConfigCarriesTraceMdcAndLokiAppender() throws Exception {
        ClassPathResource res = new ClassPathResource("logback-spring.xml");
        assertThat(res.exists()).as("logback-spring.xml is on the classpath").isTrue();

        String xml;
        try (InputStream in = res.getInputStream()) {
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        // well-formed XML (parses without error)
        try (InputStream in = res.getInputStream()) {
            DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
        }

        // the console pattern pulls the trace context out of the MDC on every line
        assertThat(xml)
                .as("console pattern carries the trace-id from the MDC")
                .contains("%X{traceId");
        assertThat(xml)
                .as("console pattern carries the span-id from the MDC")
                .contains("%X{spanId");

        // the Loki appender exists, is gated by the `loki` profile, and its JSON message carries the traceId
        assertThat(xml).contains("com.github.loki4j.logback.Loki4jAppender");
        assertThat(xml).contains("<springProfile name=\"loki\">");
        assertThat(xml)
                .as("Loki JSON message includes the traceId so logs correlate to traces")
                .contains("\"traceId\":\"%X{traceId");
    }

    @Test
    @DisplayName("provisioned Grafana Loki datasource is well-formed and links log traceId → trace")
    @SuppressWarnings("unchecked")
    void grafanaLokiDatasourceIsWellFormedAndCorrelatesTraces() throws Exception {
        Path yml = locateRepoFile("grafana/provisioning/datasources/loki.yml");
        assertThat(Files.exists(yml))
                .as("grafana/provisioning/datasources/loki.yml must exist at %s", yml)
                .isTrue();

        String text = Files.readString(yml, StandardCharsets.UTF_8);

        // well-formed YAML with the expected datasource shape
        Map<String, Object> root = new Yaml().load(text);
        assertThat(root).as("YAML parses to a mapping").isNotNull();
        assertThat(root.get("apiVersion")).isEqualTo(1);
        List<Map<String, Object>> datasources = (List<Map<String, Object>>) root.get("datasources");
        assertThat(datasources).as("has a datasources list").isNotEmpty();
        Map<String, Object> loki = datasources.get(0);
        assertThat(loki.get("type")).isEqualTo("loki");
        assertThat(loki.get("uid")).isEqualTo("orderhub-loki");
        assertThat(String.valueOf(loki.get("url"))).contains("loki:3100");   // the compose service, not localhost

        // the derived field extracts the traceId from the log line and turns it into a trace link
        assertThat(text)
                .as("a derived field references the traceId field for logs↔traces correlation")
                .contains("traceId")
                .contains("derivedFields");
    }

    // Walk up from the module's working directory to the repo-root file, so the test works whether Surefire runs
    // from the order-service module dir or the reactor root (same trick as GrafanaDashboardTest).
    private static Path locateRepoFile(String relative) {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return Paths.get(System.getProperty("user.dir")).resolve("..").resolve(relative).normalize();
    }
}
