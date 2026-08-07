# k6 load testing — Day 47 (Phase 6 · DEPLOY)

Days 45–46 proved OrderHub is **correct** (CI runs 166 tests) and **deployable without dropping a
request** (zero-downtime rollout). Neither says how it behaves **under load**. Day 47 adds a
[Grafana k6](https://k6.io) load-test suite that drives a realistic order journey from a growing
pool of virtual users and — the whole point — **fails the build when a Service-Level Objective
(SLO) is breached**.

## The scripts

| file            | what it is        | shape                                                    |
|-----------------|-------------------|----------------------------------------------------------|
| `smoke.js`      | smoke test        | 1 VU, 3 iterations — "is it up and does the happy path work?" |
| `order-load.js` | the load test     | staged VU ramp (0→20→50→0) over ~4 min against the order API  |

Both target `${BASE_URL}/api/orders` and exercise the real endpoints:
`POST /api/orders` (create) → `GET /api/orders/{id}` (get) → `GET /api/orders?page=&size=&sort=` (list).

## Running it

Install k6 (`brew install k6`, `choco install k6`, or the Docker image `grafana/k6`), start the
stack, then:

```bash
# smoke first (cheap go/no-go)
k6 run k6/smoke.js

# the full load test against the API gateway (Day 20) on :8080 — the public front door
k6 run k6/order-load.js

# or straight at order-service on :8082, or any deployed env
k6 run -e BASE_URL=http://localhost:8082 k6/order-load.js
k6 run -e BASE_URL=https://orderhub.example.com k6/order-load.js

# with the Docker image (no local install)
docker run --rm -i --network host -e BASE_URL=http://localhost:8080 \
  -v "$PWD/k6:/k6" grafana/k6 run /k6/order-load.js
```

`BASE_URL` defaults to `http://localhost:8080` (the gateway). Nothing is hard-coded — point it
anywhere with `-e BASE_URL=…`.

## The load PROFILE — `options.stages`

`order-load.js` uses a classic **load-test** shape (warm up → hold → push → hold → drain):

```
{ duration: '30s', target: 20 }   // ramp-up   0 → 20 VUs
{ duration: '1m',  target: 20 }   // steady    hold 20 VUs   ← SLO measured here
{ duration: '30s', target: 50 }   // ramp-up  20 → 50 VUs
{ duration: '1m',  target: 50 }   // steady    hold 50 VUs
{ duration: '30s', target: 0  }   // ramp-down 50 →  0 VUs
```

A **VU (virtual user)** loops the `default()` function as fast as it can, minus a small random
think-time `sleep()` so the VU *count* — not a busy-loop — controls the offered load. `target` is
the VU count k6 moves toward linearly over each stage.

- **Load test** (this): does it hold its SLO at *expected* traffic? (steady stages)
- **Stress test**: raise `target` until it breaks — find the ceiling.
- **Soak test**: hold a moderate `target` for *hours* — find leaks / slow degradation.

## The pass/fail GATES — `options.thresholds`

Thresholds turn measurements into a **PASS/FAIL** the pipeline enforces. If any expression is false
at the end, **k6 exits non-zero** and the CI load-test job goes red.

| threshold                         | meaning                                                        |
|-----------------------------------|----------------------------------------------------------------|
| `http_req_duration: p(95)<500`    | 95% of all requests finish under 500ms                         |
| `http_req_duration: p(99)<800`    | 99% under 800ms — bounds the slow tail                         |
| `http_req_failed: rate<0.01`      | fewer than 1% of requests fail (availability SLO)              |
| `order_create_duration: p(95)<400`| the **write path** specifically stays snappy (custom `Trend`)  |
| `order_flow_success: rate>0.99`   | 99%+ of full create→get→list journeys succeed (custom `Rate`)  |
| `checks: rate>0.99`               | 99%+ of `check()` assertions pass                              |

### Why p95/p99, not the average

The **average hides the tail**. A run can average 120ms while 1 in 20 users waits 2s — the average
looks fine, those users don't. p95 (the value 95% of requests come in *under*) is what a real user
is likely to feel; p99 bounds the worst-case tail. SLOs are written on percentiles, so the
thresholds are too.

## Custom metrics

Beyond k6's built-ins, `order-load.js` records:

- `order_create_duration` (`Trend`) — latency of just the `POST` write path.
- `order_flow_success` (`Rate`) — fraction of *end-to-end* journeys (create+get+list) that fully passed.
- `orders_created` (`Counter`) — how many orders were placed.

## Reading the summary

`handleSummary()` runs once at the end and writes:

- **`k6/summary.json`** — the full aggregated result (every metric, every percentile, threshold
  pass/fail). This is the machine-readable artifact the CI job uploads; feed it to a dashboard or
  diff it between runs.
- **stdout** — a compact human summary with the p95/p99, error rate, and a ✓/✗ line per threshold.

A sample tail:

```
══════════ OrderHub k6 load test — summary ══════════
  http reqs                  18240
  orders created             6079
  http_req_duration avg      74.2ms
  http_req_duration p95      312.5ms
  http_req_duration p99      556.9ms
  http_req_failed rate       0.02%
  checks passed              99.98%

  ── threshold gates ──
    ✓ PASS  http_req_duration p(95)<500
    ✓ PASS  http_req_duration p(99)<800
    ✓ PASS  http_req_failed rate<0.01
    ✓ PASS  order_create_duration p(95)<400
    ✓ PASS  order_flow_success rate>0.99
    ✓ PASS  checks rate>0.99
═════════════════════════════════════════════════════
```

## CI gating

`.github/workflows/ci.yml` has a **`load-test`** job (`needs: build-test`, so it only runs on a
green reactor). It packages `order-service`, boots the jar standalone (H2, events/saga off — no
Kafka needed), waits for `/actuator/health` to go UP, installs k6 via
[`grafana/setup-k6-action`](https://github.com/grafana/setup-k6-action), runs the smoke then the
load test, and uploads `summary.json`. Because a breached threshold makes `k6 run` exit non-zero,
**a performance regression fails the pipeline** — the same way a failing unit test does.

## Static validation

`K6LoadTestTest` (in `order-service`, beside the other Day 43–46 deploy tests) parses this script as
text and the CI workflow with SnakeYAML — no k6 binary needed in the unit build — and asserts the
script keeps its stages, its thresholds, its `default` + `handleSummary` exports, that it hits the
real order endpoints, and that CI still has a k6 job. Weaken any of those and the reactor goes red.
