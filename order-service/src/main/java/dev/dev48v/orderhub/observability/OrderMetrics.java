package dev.dev48v.orderhub.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

// Day 37 — the custom, domain-level meters for the order flow, all registered against the ONE Micrometer
// MeterRegistry the app already has (JVM/HTTP/Resilience4j meters live there too). This is deliberately NOT a
// toy bean: OrderService injects it and feeds it from the REAL create/confirm paths, so every meter here is a
// true measurement of production behaviour. When the Prometheus registry is on the classpath, all three below
// are scraped from /actuator/prometheus.
//
// The three meter TYPES, and why each fits (the RED method — Rate, Errors, Duration):
//   • Counter  "orders.placed"     — a monotonically increasing count of placement ATTEMPTS, tagged by
//                                     `outcome` (success / rejected / reservation_failed). A counter only ever
//                                     goes up; Prometheus computes RATE and ERROR-rate from it with rate(...).
//                                     Rendered by the Prometheus registry as `orders_placed_total{outcome="…"}`
//                                     (Micrometer appends the _total suffix for counters).
//   • Timer    "order.processing"  — DURATION: wall-clock time to place an order end to end. A Timer records
//                                     both a count and a sum (and a max), so you get throughput AND latency from
//                                     one meter. Its base unit is seconds, so Prometheus exposes
//                                     `order_processing_seconds_count/_sum/_max` (+ histogram buckets).
//   • Gauge    "orders.open"       — a value that goes UP and DOWN: orders currently PLACED but not yet
//                                     CONFIRMED (in flight). A gauge samples an instantaneous level, so it binds
//                                     to a live source (the AtomicInteger below) rather than being "set" — the
//                                     registry reads the current value at scrape time. Rendered as `orders_open`.
//
// Naming: we use Micrometer's dotted, backend-neutral names (orders.placed, order.processing, orders.open); each
// registry translates them to its own convention — the Prometheus registry emits the snake_case + _total/_seconds
// forms above. That is why the code names and the scraped names differ, by design.
public class OrderMetrics {

    // Micrometer meter names (dotted, backend-neutral). See the class doc for the Prometheus-rendered forms.
    public static final String PLACED_COUNTER = "orders.placed";       // -> orders_placed_total{outcome}
    public static final String PROCESSING_TIMER = "order.processing";  // -> order_processing_seconds_*
    public static final String OPEN_GAUGE = "orders.open";             // -> orders_open

    // The `outcome` tag values for the placement counter — one dimension, three known series.
    public static final String OUTCOME_SUCCESS = "success";
    public static final String OUTCOME_REJECTED = "rejected";
    public static final String OUTCOME_RESERVATION_FAILED = "reservation_failed";

    private final MeterRegistry registry;
    private final Timer processingTimer;

    // The live source the gauge samples. Incremented when an order is placed, decremented when confirmed, so at
    // any instant it reads the number of orders still in the PLACED (in-flight) state.
    private final AtomicInteger openOrders = new AtomicInteger(0);

    public OrderMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.processingTimer = Timer.builder(PROCESSING_TIMER)
                .description("Wall-clock time to place an order end to end (reserve stock, persist, announce)")
                .publishPercentileHistogram()
                .register(registry);

        // Bind the gauge to the AtomicInteger — the registry reads AtomicInteger::get at every scrape.
        Gauge.builder(OPEN_GAUGE, openOrders, AtomicInteger::get)
                .description("Orders currently PLACED but not yet CONFIRMED (in flight)")
                .register(registry);

        // Pre-register the counter for every known outcome so each series exists (at 0) from startup — a
        // scrape then shows orders_placed_total{outcome="rejected"} 0.0 rather than the series being absent
        // until the first rejection, which makes rate()/alerting well-behaved.
        counter(OUTCOME_SUCCESS);
        counter(OUTCOME_REJECTED);
        counter(OUTCOME_RESERVATION_FAILED);
    }

    // Counters are identified by name + tags; builder().register() is idempotent, so this returns the existing
    // series for a known outcome or lazily creates one for a new outcome value.
    private Counter counter(String outcome) {
        return Counter.builder(PLACED_COUNTER)
                .description("Total order-placement attempts, tagged by outcome")
                .tag("outcome", outcome)
                .register(registry);
    }

    // Called once per placement attempt (from OrderService's finally block): bump the outcome-tagged counter
    // and record how long the attempt took into the Timer.
    public void recordPlacement(String outcome, long durationNanos) {
        counter(outcome).increment();
        processingTimer.record(Duration.ofNanos(durationNanos));
    }

    // A new order entered the PLACED state — one more in flight.
    public void orderOpened() {
        openOrders.incrementAndGet();
    }

    // An order left the PLACED state (confirmed) — one fewer in flight. Guarded so it never goes negative.
    public void orderClosed() {
        openOrders.updateAndGet(v -> v > 0 ? v - 1 : 0);
    }

    // Visible for tests / diagnostics — the current in-flight count the gauge reports.
    public int openOrders() {
        return openOrders.get();
    }
}
