// Reproducible READ-ONLY load test for a locally running secured gateway.
// k6 run -e ACCESS_TOKEN='...' -e BASE_URL=http://localhost:8443 scripts/load-test.js
import http from 'k6/http';
import { check, sleep } from 'k6';

const base = __ENV.BASE_URL || 'http://localhost:8443';
if (!__ENV.ACCESS_TOKEN) throw new Error('ACCESS_TOKEN is required');
const headers = { Authorization: 'Bearer ' + __ENV.ACCESS_TOKEN };
const vus = Number(__ENV.VUS || 5);
const duration = __ENV.DURATION || '30s';
const product = __ENV.PRODUCT_ID; // optional; must be a known seeded product

export const options = {
  vus,
  duration,
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<1000'],
  },
};

export default function () {
  // Safe to run repeatedly: GETs do not modify carts, stock, or orders.
  const catalog = http.get(base + '/product-composite?page=0&size=10', { headers });
  check(catalog, { 'catalog read 200': (r) => r.status === 200 });
  if (product) {
    const item = http.get(base + '/product-composite/' + encodeURIComponent(product), { headers });
    check(item, { 'seed product 200': (r) => r.status === 200 });
  }
  sleep(1);
}
