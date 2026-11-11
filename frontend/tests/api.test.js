import test from "node:test";
import assert from "node:assert/strict";
import { createApiClient, ApiError } from "../api.js";

function fixture({
  status = 200, body = {}, contentType = "application/json",
  customer = "customer-token", operator = "operator-token",
} = {}) {
  const calls = [];
  const client = createApiClient({
    getToken: () => customer,
    getOperatorToken: () => operator,
    fetchImpl: async (path, init) => {
      calls.push({ path, ...init });
      return new Response(status === 204 || status === 202 ? null : JSON.stringify(body), {
        status,
        headers: { "content-type": contentType },
      });
    },
  });
  return { client, calls };
}

test("catalog encodes search filters, sort and page on the same origin", async () => {
  const { client, calls } = fixture({ body: { products: [], totalElements: 0 } });
  await client.catalog({ query: "headphones & speakers", category: "home", page: 2 });
  assert.equal(calls.length, 1);
  const url = new URL(calls[0].path, "http://storefront.local");
  assert.equal(url.origin, "http://storefront.local");
  assert.equal(url.pathname, "/api/product-composite");
  assert.equal(url.searchParams.get("query"), "headphones & speakers");
  assert.equal(url.searchParams.get("category"), "home");
  assert.equal(url.searchParams.get("page"), "2");
  assert.equal(calls[0].headers.Authorization, "Bearer customer-token");
});

test("health is public and maps to the special nginx health path", async () => {
  const { client, calls } = fixture({ customer: "", operator: "", body: { status: "UP" } });
  assert.deepEqual((await client.health()).data, { status: "UP" });
  assert.equal(calls[0].path, "/api/health");
  assert.equal(calls[0].headers.Authorization, undefined);
});

test("cart and checkout require a user access token, not operator token", async () => {
  let touched = false;
  const client = createApiClient({
    getToken: () => "", getOperatorToken: () => "operator",
    fetchImpl: async () => { touched = true; throw Error("must not fetch"); },
  });
  await assert.rejects(client.cart(), error =>
    error instanceof ApiError && error.message.includes("signed-in user"));
  await assert.rejects(client.checkout("original-key"), ApiError);
  assert.equal(touched, false);
});

test("checkout reuses the caller-supplied idempotency key", async () => {
  const { client, calls } = fixture({ body: { orderId: "demo-order" } });
  await client.checkout("checkout-once-123");
  await client.checkout("checkout-once-123");
  assert.equal(calls[0].path, "/api/product-composite/orders");
  assert.equal(calls[0].method, "POST");
  assert.equal(calls[0].headers["Idempotency-Key"], "checkout-once-123");
  assert.equal(calls[1].headers["Idempotency-Key"], "checkout-once-123");
});

test("uses operator scope only for recovery overview", async () => {
  const { client, calls } = fixture();
  await client.overview();
  await client.reviewQueue();
  await client.cart();
  assert.equal(calls[0].headers.Authorization, "Bearer operator-token");
  assert.equal(calls[1].headers.Authorization, "Bearer operator-token");
  assert.equal(calls[2].headers.Authorization, "Bearer customer-token");
});

test("protects against external or malformed API paths", async () => {
  const { client } = fixture();
  for (const path of ["https://attacker.invalid", "//evil", "/other", "/product-composite\\evil",
    "/product-composite#token", "/product-composite://evil"]) {
    await assert.rejects(client.request(path), ApiError);
  }
});

test("maps errors safely without reflecting remote credentials or response body", async () => {
  const client = createApiClient({
    getToken: () => "secret-jwt",
    fetchImpl: async () => new Response("secret-jwt and backend stack trace",
      { status: 403, headers: { "content-type": "text/plain" } }),
  });
  await assert.rejects(client.cart(), error => {
    assert.equal(error.status, 403);
    assert.ok(!error.message.includes("secret-jwt"));
    assert.ok(!error.message.includes("stack trace"));
    return true;
  });
});

test("202 Accepted remains an asynchronous event intent, not a product", async () => {
  const { client } = fixture({ status: 202 });
  const result = await client.createProduct({ productId: 800001, name: "Demo" });
  assert.deepEqual(result, { status: 202, data: null });
});

test("handles malformed JSON and unexpected content type", async () => {
  const client = createApiClient({
    getToken: () => "token",
    fetchImpl: async () => new Response("not-json", {
      headers: { "content-type": "application/json" },
    }),
  });
  await assert.rejects(client.cart(), /malformed JSON/);
  const wrongType = fixture({ contentType: "text/html" });
  await assert.rejects(wrongType.client.cart(), /unexpected response format/);
});

test("network failures are safe and never expose token values", async () => {
  const client = createApiClient({
    getToken: () => "my-secret-token",
    fetchImpl: async () => { throw Error("my-secret-token from browser stack"); },
  });
  await assert.rejects(client.cart(), error => {
    assert.ok(error instanceof ApiError);
    assert.ok(!error.message.includes("my-secret-token"));
    return true;
  });
});
