import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { resolve, dirname } from "node:path";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const read = name => readFileSync(resolve(root, name), "utf8");

test("static frontend has no bundle dependencies or baked credentials", () => {
  const pkg = JSON.parse(read("frontend/package.json"));
  assert.equal(pkg.dependencies, undefined);
  const script = read("frontend/app.js");
  assert.ok(!/localStorage|sessionStorage|document\.cookie/.test(script));
  assert.ok(script.includes("data-action"));
  const template = read("frontend/index.html");
  assert.ok(template.includes('type="module" src="/app.js"'));
});

test("nginx proxies same-origin requests to the existing gateway and enforces CSP", () => {
  const nginx = read("frontend/nginx.conf");
  assert.match(nginx, /location \/api\//);
  assert.match(nginx, /proxy_pass http:\/\/gateway:8443\//);
  assert.match(nginx, /location = \/api\/health/);
  assert.match(nginx, /proxy_pass http:\/\/gateway:8443\/actuator\/health/);
  assert.match(nginx, /Content-Security-Policy/);
  assert.match(nginx, /no-store/);
});

test("compose publishes the storefront on localhost only", () => {
  const compose = read("docker-compose-frontend.yml");
  assert.match(compose, /127\.0\.0\.1:8088:80/);
  assert.match(compose, /build: \.\/frontend/);
  assert.ok(!compose.includes("8443:8443"));
});

test("frontend startup uses combined Compose files, not direct backend bypass", () => {
  const script = read("scripts/start-storefront.sh");
  assert.match(script, /docker-compose\.yml/);
  assert.match(script, /docker-compose-frontend\.yml/);
  assert.match(script, /compose\[@\]/);
  assert.match(script, /config --quiet/);
});
