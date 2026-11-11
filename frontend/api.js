/**
 * Dependency-free adapter for the Spring product-composite API.
 * All API requests use /api on this origin through the nginx gateway proxy.
 * Tokens are supplied by an in-memory UI session; never persisted here.
 */
export class ApiError extends Error {
  constructor(message, status = 0) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

const HTTP_ERRORS = {
  400: "The request was rejected. Check its inputs.",
  401: "Your access token is missing, invalid, or expired.",
  403: "This token does not have the required permission.",
  404: "The requested item could not be found.",
  409: "The operation conflicts with the current state.",
  422: "The backend could not process this request.",
  429: "Too many requests. Please try again shortly.",
  502: "The API gateway cannot reach a backend service.",
  503: "A backend service is unavailable.",
};

function cleanPath(path) {
  if (typeof path !== "string" ||
      !(path === "/health" || path === "/product-composite" ||
        path.startsWith("/product-composite?") ||
        path.startsWith("/product-composite/")) ||
      path.includes("://") || path.includes("\\") || path.includes("#") ||
      path.startsWith("//")) {
    throw new ApiError("An unsupported API path was requested.");
  }
  return path;
}

function params(values) {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(values)) {
    if (value !== "" && value !== null && value !== undefined) query.set(key, String(value));
  }
  return query.toString();
}

export function createApiClient({
  fetchImpl = globalThis.fetch,
  getToken = () => "",
  getOperatorToken = () => "",
} = {}) {
  async function request(path, {
    method = "GET", body, operator = false, publicEndpoint = false,
    headers = {},
  } = {}) {
    const resource = cleanPath(path);
    const token = operator ? getOperatorToken() : getToken();
    if (!publicEndpoint && !token) {
      throw new ApiError(operator
        ? "Connect an operator token with fulfillment:read permission."
        : "Connect a signed-in user access token to continue.");
    }

    const requestHeaders = { Accept: "application/json", ...headers };
    if (!publicEndpoint) requestHeaders.Authorization = "Bearer " + token;
    if (body !== undefined) requestHeaders["Content-Type"] = "application/json";

    let response;
    try {
      response = await fetchImpl("/api" + resource, {
        method,
        headers: requestHeaders,
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
        cache: "no-store",
      });
    } catch {
      // Do not expose URLs, bearer tokens, or response bodies to end users.
      throw new ApiError("Cannot reach the local API. Check Docker Compose.");
    }

    if (!response.ok) {
      throw new ApiError(HTTP_ERRORS[response.status] ||
        "The API returned HTTP " + response.status + ".", response.status);
    }

    if (response.status === 204 || response.status === 202) {
      return { status: response.status, data: null };
    }

    const type = response.headers.get("content-type") || "";
    if (!type.includes("application/json")) {
      throw new ApiError("The API returned an unexpected response format.", response.status);
    }

    try {
      return { status: response.status, data: await response.json() };
    } catch {
      throw new ApiError("The API returned malformed JSON.", response.status);
    }
  }

  return {
    request,
    health: () => request("/health", { publicEndpoint: true }),
    catalog: (filters = {}) =>
      request("/product-composite?" + params({
        query: filters.query, category: filters.category,
        sort: filters.sort || "productIdDesc",
        page: filters.page ?? 0, size: filters.size ?? 12,
      })),
    product: (id) => request("/product-composite/" + encodeURIComponent(id)),
    createProduct: (data) => request("/product-composite", { method: "POST", body: data }),
    cart: () => request("/product-composite/cart"),
    setCartItem: (id, quantity) => request(
      "/product-composite/cart/items/" + encodeURIComponent(id),
      { method: "PUT", body: { quantity } }),
    removeCartItem: (id) => request(
      "/product-composite/cart/items/" + encodeURIComponent(id), { method: "DELETE" }),
    checkout: (key) => request("/product-composite/orders", {
      method: "POST", headers: { "Idempotency-Key": key },
    }),
    orders: (page = 0) =>
      request("/product-composite/orders/history?" + params({ page, size: 20 })),
    order: (id) => request("/product-composite/orders/" + encodeURIComponent(id)),
    timeline: (id) => request(
      "/product-composite/orders/" + encodeURIComponent(id) + "/timeline"),
    wishlist: () => request("/product-composite/wishlist?page=0&size=50"),
    save: (id) => request(
      "/product-composite/wishlist/items/" + encodeURIComponent(id), { method: "PUT" }),
    unsave: (id) => request(
      "/product-composite/wishlist/items/" + encodeURIComponent(id), { method: "DELETE" }),
    overview: () => request("/product-composite/fulfillment/recovery/overview",
      { operator: true }),
    reviewQueue: () => request(
      "/product-composite/fulfillment/supervisions/review-queue?limit=10",
      { operator: true }),
  };
}
