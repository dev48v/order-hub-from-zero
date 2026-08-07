// Day 47 (Phase 6 — DEPLOY) — the LOAD TEST for the OrderHub order API.
// ============================================================================
// Day 45 made a `git push` build + test + image the reactor; Day 46 made the ROLLOUT drop-free.
// Both prove the system is CORRECT and DEPLOYABLE — neither says anything about how it behaves
// UNDER LOAD. Day 47 closes that gap with Grafana k6: a script that drives a realistic order
// journey (create → get → list) from a growing pool of virtual users (VUs), measures latency and
// error rate, and — crucially — FAILS THE BUILD if either crosses a Service-Level Objective (SLO).
//
// WHY k6: the whole test is code (this file, reviewed in the same PR as the app), it runs the same
// on a laptop and on a CI runner, and its THRESHOLDS turn "the p95 was 480ms" from a number a human
// has to eyeball into a hard PASS/FAIL gate the pipeline enforces automatically.
//
// Run it:   k6 run k6/order-load.js
//           k6 run -e BASE_URL=http://localhost:8080 k6/order-load.js     (point at the gateway)
//           k6 run -e BASE_URL=http://localhost:8082 k6/order-load.js     (straight at order-service)
//
// The default target is the API gateway (Day 20) on :8080, whose `Path=/api/orders/**` route
// forwards to order-service by discovery — i.e. we load the SAME public front door real clients use.

import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';

// ── configuration (all overridable with -e KEY=value, nothing hard-coded) ───────────────────
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ORDERS = `${BASE_URL}/api/orders`;

// ── custom metrics ──────────────────────────────────────────────────────────────────────────
// Built-in metrics (http_req_duration, http_req_failed, checks) cover HTTP as a whole. These
// custom ones isolate the parts we care about so a threshold can gate them SPECIFICALLY:
//   orderCreateDuration — how long just the POST /api/orders (the write path) takes,
//   orderFlowSuccess    — the rate of FULLY-successful create→get→list journeys (a business SLO),
//   ordersCreated       — a simple count of orders placed, for the summary.
const orderCreateDuration = new Trend('order_create_duration', true); // true → report as a time (ms)
const orderFlowSuccess = new Rate('order_flow_success');
const ordersCreated = new Counter('orders_created');

// ── options: the load PROFILE (stages) + the pass/fail GATES (thresholds) ───────────────────
export const options = {
  // STAGES = the VU ramp over time. This is a classic LOAD test shape (not a spike/stress test):
  // warm up, hold a steady realistic load, then ramp down. `target` is the VU count k6 linearly
  // moves toward over `duration`; VUs each loop the default() function as fast as they can (minus
  // our think-time sleep). ~4 minutes total.
  stages: [
    { duration: '30s', target: 20 },  // ramp-up:   0 → 20 VUs (warm the JIT, caches, connection pool)
    { duration: '1m', target: 20 },   // steady:    hold 20 VUs (the SLO is measured HERE)
    { duration: '30s', target: 50 },  // ramp-up:  20 → 50 VUs (push past the comfortable load)
    { duration: '1m', target: 50 },   // steady:    hold 50 VUs
    { duration: '30s', target: 0 },   // ramp-down:50 →  0 VUs (drain — no abrupt stop)
  ],

  // THRESHOLDS = the SLOs, as PASS/FAIL gates. If any expression is false at the end of the run,
  // k6 exits NON-ZERO — which is what makes the CI load-test job go red on a regression. Each is a
  // real objective, not a vanity number:
  thresholds: {
    // 95% of ALL requests finish under 500ms and 99% under 800ms. p95/p99 (not the average!) are
    // what users actually feel — the average hides the slow tail. abortOnFail stops the run early
    // (and cheaply) once an SLO is already, definitively breached.
    http_req_duration: [
      { threshold: 'p(95)<500', abortOnFail: false },
      { threshold: 'p(99)<800', abortOnFail: false },
    ],
    // Fewer than 1% of requests may fail (non-2xx/3xx or transport error). This is the availability SLO.
    http_req_failed: ['rate<0.01'],
    // The WRITE path specifically must keep its p95 under 400ms (creates are the expensive path).
    order_create_duration: ['p(95)<400'],
    // At least 99% of full create→get→list journeys must succeed end-to-end (the business SLO).
    order_flow_success: ['rate>0.99'],
    // At least 99% of all check() assertions must pass.
    checks: ['rate>0.99'],
  },
};

// A small pool of realistic items so every VU isn't ordering the identical thing.
const ITEMS = ['Mechanical keyboard', 'USB-C hub', '27-inch monitor', 'Noise-cancelling headset', 'Standing desk'];
const CUSTOMERS = ['Ada Lovelace', 'Grace Hopper', 'Alan Turing', 'Katherine Johnson', 'Edsger Dijkstra'];

function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

// ── the scenario: one virtual user's realistic order journey, run in a loop ─────────────────
export default function () {
  const params = { headers: { 'Content-Type': 'application/json' } };
  let flowOk = true;

  // 1) CREATE — POST /api/orders. The write path. We time it into our custom Trend and check the
  //    201 + a Location header + a body carrying the same fields we sent.
  const payload = JSON.stringify({
    customer: pick(CUSTOMERS),
    item: pick(ITEMS),
    quantity: Math.floor(Math.random() * 5) + 1, // 1..5 (well within the 1..1000 bound)
  });
  const createRes = http.post(ORDERS, payload, { ...params, tags: { name: 'create-order' } });
  orderCreateDuration.add(createRes.timings.duration);

  const created = check(createRes, {
    'create → 201': (r) => r.status === 201,
    'create → has Location header': (r) => !!r.headers['Location'],
    'create → body has id': (r) => {
      try { return typeof r.json('id') === 'string' && r.json('id').length > 0; }
      catch (e) { return false; }
    },
    'create → status PLACED': (r) => {
      try { return r.json('status') === 'PLACED'; } catch (e) { return false; }
    },
  });
  if (!created) {
    flowOk = false;
    orderFlowSuccess.add(false);
    sleep(1);
    return; // nothing to GET if the create failed
  }
  ordersCreated.add(1);
  const id = createRes.json('id');

  // brief think-time — a real user doesn't fire the next request in the same microsecond.
  sleep(Math.random() * 0.3 + 0.2); // 0.2–0.5s

  // 2) GET — GET /api/orders/{id}. The read-by-id path. Check the 200 + that we got back the order
  //    we just created.
  const getRes = http.get(`${ORDERS}/${id}`, { tags: { name: 'get-order' } });
  const gotOne = check(getRes, {
    'get → 200': (r) => r.status === 200,
    'get → same id': (r) => {
      try { return r.json('id') === id; } catch (e) { return false; }
    },
  });
  flowOk = flowOk && gotOne;

  sleep(Math.random() * 0.3 + 0.2);

  // 3) LIST — GET /api/orders?page=&size=&sort= (paginated). Check the 200 + a well-formed
  //    content + page-metadata envelope (PagedResponse).
  const listRes = http.get(`${ORDERS}?page=0&size=10&sort=createdAt,desc`, { tags: { name: 'list-orders' } });
  const listed = check(listRes, {
    'list → 200': (r) => r.status === 200,
    'list → has content array': (r) => {
      try { return Array.isArray(r.json('content')); } catch (e) { return false; }
    },
  });
  flowOk = flowOk && listed;

  // Record whether this VU's ENTIRE journey succeeded (feeds the order_flow_success SLO).
  orderFlowSuccess.add(flowOk);

  // Pace the loop so the VU count — not a busy-loop — controls the offered load.
  sleep(Math.random() * 0.5 + 0.5); // 0.5–1.0s
}

// ── handleSummary: write a machine-readable JSON report + a human summary to stdout ─────────
// k6 calls this ONCE at the end with the aggregated `data`. Returning a map of path → content lets
// us emit whatever artifacts we like. We write summary.json (consumed by the CI artifact upload /
// dashboards) and print a compact text summary so a human reading the CI log sees the verdict.
export function handleSummary(data) {
  return {
    'k6/summary.json': JSON.stringify(data, null, 2),
    stdout: textSummary(data),
  };
}

// A tiny self-contained text summary (no remote jslib import, so it runs fully offline / air-gapped).
function textSummary(data) {
  const m = data.metrics;
  const line = (label, v) => `  ${label.padEnd(26)} ${v}`;
  const ms = (x) => (x === undefined ? 'n/a' : `${x.toFixed(1)}ms`);
  const dur = m.http_req_duration ? m.http_req_duration.values : {};
  const failed = m.http_req_failed ? m.http_req_failed.values : {};
  const checks = m.checks ? m.checks.values : {};
  const created = m.orders_created ? m.orders_created.values.count : 0;

  const thresholdVerdict = (name) => {
    const metric = m[name];
    if (!metric || !metric.thresholds) return '';
    return Object.entries(metric.thresholds)
      .map(([expr, res]) => `    ${res.ok ? '✓ PASS' : '✗ FAIL'}  ${name} ${expr}`)
      .join('\n');
  };

  let out = '\n══════════ OrderHub k6 load test — summary ══════════\n';
  out += line('http reqs', m.http_reqs ? m.http_reqs.values.count : 0) + '\n';
  out += line('orders created', created) + '\n';
  out += line('http_req_duration avg', ms(dur.avg)) + '\n';
  out += line('http_req_duration p95', ms(dur['p(95)'])) + '\n';
  out += line('http_req_duration p99', ms(dur['p(99)'])) + '\n';
  out += line('http_req_failed rate', ((failed.rate || 0) * 100).toFixed(2) + '%') + '\n';
  out += line('checks passed', ((checks.rate || 0) * 100).toFixed(2) + '%') + '\n';
  out += '\n  ── threshold gates ──\n';
  for (const name of ['http_req_duration', 'http_req_failed', 'order_create_duration', 'order_flow_success', 'checks']) {
    const v = thresholdVerdict(name);
    if (v) out += v + '\n';
  }
  out += '═════════════════════════════════════════════════════\n';
  return out;
}
