// Day 47 — the SMOKE test: the cheapest possible k6 run.
// ============================================================================
// A smoke test is NOT a load test. It runs ONE virtual user for a couple of iterations, just to
// answer "is the order API up and does the happy path work AT ALL?" — before you spend four minutes
// (and CI runner time) on the full order-load.js ramp. It's the go/no-go gate you run first: if the
// smoke fails, there's no point load-testing a broken build.
//
// Run it:   k6 run k6/smoke.js
//           k6 run -e BASE_URL=http://localhost:8082 k6/smoke.js
//
// The thresholds here are lenient (it's a warm-up, not the SLO) — but http_req_failed<0.01 still
// makes a smoke failure exit non-zero so CI can gate on it.

import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ORDERS = `${BASE_URL}/api/orders`;

export const options = {
  vus: 1,
  iterations: 3,
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1000'], // generous — a cold JVM's first requests are slow
    checks: ['rate>0.99'],
  },
};

export default function () {
  const params = { headers: { 'Content-Type': 'application/json' } };

  // create
  const createRes = http.post(
    ORDERS,
    JSON.stringify({ customer: 'Smoke Tester', item: 'Smoke item', quantity: 1 }),
    params,
  );
  check(createRes, {
    'smoke create → 201': (r) => r.status === 201,
    'smoke create → has id': (r) => {
      try { return typeof r.json('id') === 'string'; } catch (e) { return false; }
    },
  });

  if (createRes.status === 201) {
    const id = createRes.json('id');
    // get it back
    const getRes = http.get(`${ORDERS}/${id}`);
    check(getRes, {
      'smoke get → 200': (r) => r.status === 200,
      'smoke get → same id': (r) => {
        try { return r.json('id') === id; } catch (e) { return false; }
      },
    });
  }

  // list
  const listRes = http.get(ORDERS);
  check(listRes, { 'smoke list → 200': (r) => r.status === 200 });

  sleep(0.5);
}
