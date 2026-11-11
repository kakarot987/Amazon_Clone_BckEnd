import { createApiClient, ApiError } from "./api.js";

const state = {
  token: "", operatorToken: "", page: "shop", loading: false,
  products: [], totalPages: 0, totalElements: 0,
  query: "", category: "", sort: "productIdDesc", pageIndex: 0,
  cart: null, saved: [], orders: [], detail: null,
  overview: null, reviewQueue: [], checkoutKey: null, error: "",
};
const api = createApiClient({
  getToken: () => state.token,
  getOperatorToken: () => state.operatorToken,
});
const app = document.querySelector("#app");
const countNode = document.querySelector("#cart-count");
const sessionDialog = document.querySelector("#session-dialog");
const createDialog = document.querySelector("#create-dialog");

function esc(value) {
  return String(value ?? "").replace(/[&<>"']/g, (char) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;",
  })[char]);
}
function validId(value) {
  const number = Number(value);
  return Number.isSafeInteger(number) && number > 0 && number <= 2147483647;
}
function categoryClass(value) {
  const cat = String(value || "").toLowerCase();
  return ["electronics", "home", "fashion", "books", "accessories"].includes(cat)
    ? cat : "other";
}
function symbolFor(value) {
  return { electronics: "🎧", home: "🪑", fashion: "👟", books: "📚",
    accessories: "⌚", other: "✦" }[categoryClass(value)];
}
function currency(value, unit) {
  const safe = ["INR", "USD", "EUR", "GBP"].includes(String(unit)) ? unit : "INR";
  const amount = Number(value);
  if (!Number.isFinite(amount)) return "Price unavailable";
  return new Intl.NumberFormat("en-IN", { style: "currency", currency: safe,
    maximumFractionDigits: 2 }).format(amount);
}
function time(value) {
  const date = new Date(value);
  return Number.isFinite(date.getTime()) ? date.toLocaleDateString("en-IN", {
    day: "numeric", month: "short", year: "numeric" }) : "Not available";
}
function showToast(message, error = false) {
  const toast = document.querySelector("#toast");
  toast.textContent = message;
  toast.classList.toggle("error", error);
  toast.hidden = false;
  clearTimeout(showToast.timer);
  showToast.timer = setTimeout(() => { toast.hidden = true; }, 5200);
}
function empty(icon, title, description, action = "") {
  return '<section class="empty-state"><div class="empty-icon" aria-hidden="true">' +
    icon + '</div><h3>' + esc(title) + '</h3><p>' + esc(description) +
    '</p>' + action + "</section>";
}
function errorBox(message) {
  return '<div class="notice" role="alert">ⓘ ' + esc(message) + "</div>";
}
function heading(title, subtitle, action = "") {
  return '<div class="section-head"><div><h2>' + esc(title) + "</h2><p>" +
    esc(subtitle) + '</p></div><div class="section-actions">' + action + "</div></div>";
}
function button(label, action, extra = "", cls = "button button-primary button-small") {
  return '<button type="button" class="' + cls + '" data-action="' + esc(action) +
    '" ' + extra + ">" + esc(label) + "</button>";
}
function productCard(item, saved = false) {
  const id = Number(item.productId);
  if (!validId(id)) return "";
  const name = esc(item.name || "Untitled product");
  const category = categoryClass(item.category);
  const stock = item.stockQuantity;
  const inStock = stock == null || Number(stock) > 0;
  const displayPrice = currency(item.price, item.currency);
  const unavailable = item.productExists === false;
  return '<article class="product-card">' +
    '<div class="product-visual ' + category + '">' +
    '<span class="product-tag">' + esc(category) + '</span>' +
    '<span class="symbol" aria-hidden="true">' + symbolFor(category) + '</span>' +
    '<button class="save-button ' + (saved ? "is-saved" : "") +
    '" data-action="' + (saved ? "unsave" : "save") + '" data-id="' + id +
    '" aria-label="' + (saved ? "Remove from wishlist" : "Save to wishlist") +
    '" title="Wishlist">' + (saved ? "♥" : "♡") + "</button></div>" +
    '<div class="product-body"><span class="product-meta">Item #' + id + '</span>' +
    '<h3 class="product-name"><button data-action="detail" data-id="' + id +
    '">' + name + "</button></h3>" +
    '<div class="product-bottom"><span class="price">' + esc(displayPrice) +
    '</span><span class="stock ' + (!inStock ? "low" : "") + '">' +
    (unavailable ? "Removed" : inStock ? "● Available" : "Out of stock") +
    '</span></div><div class="card-actions">' +
    button("Details", "detail", 'data-id="' + id + '"', "button button-outline button-small") +
    button("Add to bag +", "add", 'data-id="' + id + '"' +
      (!inStock || unavailable ? " disabled" : ""), "button button-primary button-small") +
    "</div></div></article>";
}
function shop() {
  const cards = state.products.map((p) => productCard(p)).join("");
  const hero = '<section class="hero"><div class="hero-copy"><p class="eyebrow">THE DEVELOPMENT STORE</p>' +
    '<h1>Good things.<br><em>Built differently.</em></h1>' +
    '<p>A modern storefront on top of a serious event-driven Java backend. Explore the catalog, build a cart and try a simulated checkout.</p>' +
    '<button class="button button-primary" data-action="focus-search">Explore collection ↗</button>' +
    '</div><div class="hero-art" aria-hidden="true"><span class="hero-illustration">🎧</span>' +
    '<div class="hero-note">A better shopping demo ✦</div></div></section>';
  const controls = '<form class="toolbar" id="catalog-search"><div class="search-wrap"><span aria-hidden="true">⌕</span>' +
    '<input id="search-input" name="query" aria-label="Search products" placeholder="Search products, categories, ideas..." value="' +
    esc(state.query) + '"></div><div class="filter-row"><select name="category" aria-label="Filter category">' +
    '<option value="">All categories</option>' +
    ["electronics", "home", "fashion", "books", "accessories"].map((cat) =>
      '<option value="' + cat + '" ' + (state.category === cat ? "selected" : "") + ">" +
      cat.charAt(0).toUpperCase() + cat.slice(1) + "</option>").join("") +
    '</select><select name="sort" aria-label="Sort catalog">' +
    [["productIdDesc", "Newest"], ["productId", "Oldest"],
      ["name", "Name A–Z"], ["stockDesc", "Stock: high to low"]].map(([value, label]) =>
      '<option value="' + value + '" ' + (state.sort === value ? "selected" : "") +
      ">" + esc(label) + "</option>").join("") +
    '</select><button class="button button-primary button-small" type="submit">Search</button></div></form>';
  const pager = state.totalPages > 1
    ? '<div class="pager">' +
      button("← Previous", "prev", state.pageIndex < 1 ? "disabled" : "", "button button-outline button-small") +
      "<span>Page " + (state.pageIndex + 1) + " of " + state.totalPages + "</span>" +
      button("Next →", "next",
        state.pageIndex + 1 >= state.totalPages ? "disabled" : "",
        "button button-outline button-small") + "</div>" : "";
  return hero + heading("Explore our collection",
    state.totalElements + " items · Real backend catalog",
    button("+ Publish demo item", "create", "", "button button-outline button-small")) +
    controls + (cards ? '<div class="product-grid">' + cards + "</div>" :
      empty("⌕", "Nothing on the shelves yet",
        "Connect your customer token, or publish a demo product with product:write.")) + pager;
}
function saved() {
  const cards = state.saved.map((p) => productCard(p, true)).join("");
  return '<div class="page-intro"><p class="eyebrow">YOUR SHORTLIST</p><h1 class="page-title">Saved for later</h1>' +
    '<p>Your wishlist is tied to your signed-in demo account—not stored in this browser.</p></div>' +
    (cards ? '<div class="product-grid">' + cards + "</div>" :
      empty("♡", "Your wishlist is looking empty",
        "Save something you love, then find it here.",
        button("Explore the catalog", "navigate", 'data-page="shop"')));
}
function cart() {
  const items = Array.isArray(state.cart?.items) ? state.cart.items : [];
  const rows = items.map((item) => {
    const id = Number(item.productId);
    if (!validId(id)) return "";
    return '<div class="line-item"><div class="item-thumb">' + symbolFor("other") +
      '</div><div class="item-copy"><h3>' + esc(item.name || "Product #" + id) +
      '</h3><p>' + esc(currency(item.unitPrice, item.currency)) + " per unit</p></div>" +
      '<div class="quantity-control">' +
      button("−", "decrement", 'data-id="' + id + '" data-quantity="' +
        Number(item.quantity) + '"', "button button-outline button-small") +
      "<span>" + Number(item.quantity) + "</span>" +
      button("+", "increment", 'data-id="' + id + '" data-quantity="' +
        Number(item.quantity) + '"', "button button-outline button-small") +
      "</div>" + button("Remove", "remove", 'data-id="' + id + '"',
        "button button-danger button-small") + "</div>";
  }).join("");
  if (!rows) return '<div class="page-intro"><p class="eyebrow">YOUR BAG</p><h1 class="page-title">Shopping bag</h1></div>' +
    empty("▣", "Your bag is empty", "Explore the collection and add an item to get started.",
      button("Shop products", "navigate", 'data-page="shop"'));
  const total = items.reduce((sum, x) => sum + Number(x.unitPrice || 0) * Number(x.quantity || 0), 0);
  const currencyCode = items.find(x => x.currency)?.currency || "INR";
  return '<div class="page-intro"><p class="eyebrow">YOUR BAG</p><h1 class="page-title">Shopping bag</h1>' +
    '<p>Adjust quantities before reserving inventory through the backend.</p></div>' +
    '<div class="row-layout"><div class="panel">' + rows + '</div><aside class="panel">' +
    '<h2 class="aside-title">Order summary</h2><div class="summary-row muted"><span>Items</span><span>' +
    items.reduce((s, x) => s + Number(x.quantity || 0), 0) + '</span></div>' +
    '<div class="summary-row summary-total"><span>Estimated total</span><span>' +
    esc(currency(total, currencyCode)) + '</span></div>' +
    '<div class="notice">ⓘ Demo checkout reserves stock only. Payment and delivery are simulated.</div>' +
    button("Reserve order →", "checkout", "", "button button-accent button-block") +
    '</aside></div>';
}
function orders() {
  const result = state.orders.map((line) => {
    const item = line?.order || line;
    if (!item?.orderId) return "";
    return '<article class="panel order-card"><div class="order-top"><div>' +
      '<p class="eyebrow">ORDER · ' + esc(String(item.orderId).slice(0, 28)) + '</p>' +
      '<h3>' + esc(item.productName || "Commerce reservation") + '</h3>' +
      '<p>Created ' + esc(time(item.createdAt)) + ' · ' +
      esc(currency(item.total, item.currency)) + '</p></div>' +
      '<span class="status-pill">' + esc(item.status || "Unknown") + '</span></div>' +
      '<div class="section-actions section-spacer">' +
      button("View order", "order", 'data-order="' + esc(item.orderId) + '"',
        "button button-outline button-small") + "</div></article>";
  }).join("");
  return '<div class="page-intro"><p class="eyebrow">YOUR ACCOUNT</p><h1 class="page-title">Order history</h1>' +
    '<p>Only orders belonging to your authenticated demo user appear here.</p></div>' +
    (result || empty("◷", "No reservations yet",
      "Your order history will appear after a successful simulated checkout.",
      button("Start shopping", "navigate", 'data-page="shop"')));
}
function detail() {
  const item = state.detail;
  if (!item) return empty("⌕", "Product not found", "Choose another item from the catalog.",
    button("Back to catalog", "navigate", 'data-page="shop"'));
  const id = Number(item.productId);
  const reviews = Array.isArray(item.reviews) ? item.reviews : [];
  const recommendations = Array.isArray(item.recommendations) ? item.recommendations : [];
  const reviewCards = reviews.map(r =>
    '<article class="review"><h4>' + esc(r.subject || r.author || "Review") +
    '</h4><p>' + esc(r.content || "No additional comment") +
    "</p></article>").join("");
  return '<div class="page-intro">' +
    button("← Back to collection", "navigate", 'data-page="shop"',
      "button button-outline button-small") + '</div>' +
    '<div class="details-hero"><div class="details-visual" aria-hidden="true">' +
    symbolFor(item.category) + '</div><div class="details-summary">' +
    '<p class="eyebrow">' + esc(item.category || "CURATED PRODUCT") + " · #" + id +
    "</p><h1>" + esc(item.name || "Unnamed product") + "</h1>" +
    '<div class="price">' + esc(currency(item.price, item.currency)) + "</div>" +
    '<p>' + esc(item.description || "Discover the details of this item from our Spring Boot catalog.") +
    '</p><div class="notice">✦ ' + esc(item.stockQuantity == null ? "Stock check available in cart" :
      item.stockQuantity > 0 ? item.stockQuantity + " units in stock" : "Currently out of stock") +
    '</div><div class="section-actions">' +
    button("Add to bag +", "add", 'data-id="' + id + '"',
      "button button-accent") + " " +
    button("Save ♡", "save", 'data-id="' + id + '"',
      "button button-outline") + '</div></div></div>' +
    '<div class="section-head"><div><h2>Community & discovery</h2>' +
    '<p>Reviews and recommendations arrive through asynchronous consumers.</p></div></div>' +
    '<div class="row-layout"><section class="panel"><h3>Reviews (' + reviews.length +
    ')</h3><div class="review-list">' + (reviewCards ||
      '<p class="subtle">No reviews have been received yet.</p>') +
    '</div></section><aside class="panel"><h3>Recommendations</h3>' +
    (recommendations.length ? recommendations.map(x =>
      '<div class="review"><h4>' + esc(x.author || "Community member") +
      '</h4><p>' + esc(x.content || "Recommended") + '</p></div>').join("") :
      '<p class="subtle">No recommendations available yet.</p>') +
    "</aside></div>";
}
function orderDetail() {
  const o = state.detail;
  if (!o) return empty("◷", "Order unavailable", "Choose an order from your history.");
  const lines = Array.isArray(o.items) ? o.items : [];
  return '<div class="page-intro">' +
    button("← All orders", "navigate", 'data-page="orders"',
      "button button-outline button-small") +
    '<p class="eyebrow section-spacer">ORDER DETAILS</p><h1 class="page-title">Reservation ' +
    esc(String(o.orderId || "").slice(0, 24)) + "</h1></div>" +
    '<div class="row-layout"><div class="panel"><p><span class="status-pill">' +
    esc(o.status || "Unknown") + "</span></p><h2>Reserved items</h2>" +
    (lines.map(x => '<div class="line-item"><div class="item-thumb">▣</div>' +
      '<div class="item-copy"><h3>' + esc(x.productName || "Product #" + x.productId) +
      '</h3><p>Quantity: ' + Number(x.quantity || 0) + '</p></div><strong>' +
      esc(currency(x.unitPrice, o.currency)) + "</strong></div>").join("") ||
    '<p class="subtle">No item details provided.</p>') + '</div>' +
    '<aside class="panel"><h3>Summary</h3><div class="summary-row"><span>Status</span><span>' +
    esc(o.status || "Unknown") + '</span></div><div class="summary-row"><span>Payment</span><span>' +
    esc(o.paymentStatus || "Not paid") + '</span></div><div class="summary-row summary-total">' +
    '<span>Total</span><span>' + esc(currency(o.total, o.currency)) + '</span></div>' +
    '<div class="notice">ⓘ This is a mock reservation, not a charged purchase.</div></aside></div>';
}
function ops() {
  const o = state.overview;
  const stat = (value, label) => '<article class="stat"><p class="eyebrow">' +
    esc(label) + '</p><div class="stat-value">' + Number(value || 0) +
    '</div><div class="stat-label">MongoDB observation</div></article>';
  const queue = state.reviewQueue.map(row =>
    '<div class="line-item"><div class="item-copy"><h3>' +
    esc(String(row.id || "").slice(0, 40)) +
    '</h3><p>Reason: ' + esc(row.reviewReason || "Review required") +
    '</p></div><span class="status-pill warning">NEEDS_REVIEW</span></div>').join("");
  return '<div class="page-intro"><p class="eyebrow">ENGINEERING VIEW</p>' +
    '<h1 class="page-title">The system behind the store</h1>' +
    '<p>Read-only operational data from the composite service. Requires a separate operator JWT with fulfillment:read.</p></div>' +
    '<div class="ops-warning">This is a portfolio observability view, not a production operations console. No replay or compensation actions are exposed.</div>' +
    (o ? '<div class="stat-grid">' + [
      stat(o.pending, "Pending"), stat(o.checking, "Checking"),
      stat(o.waiting, "Waiting"), stat(o.receiptsVerified, "Receipts verified"),
      stat(o.needsReview, "Needs review"),
      stat(o.oldestUndelivered ? 1 : 0, "Oldest queue head present"),
    ].join("") + '</div><div class="panel"><h3>Oldest unfinished event</h3>' +
      (o.oldestUndelivered ? '<p class="muted">Event ID: <code>' +
      esc(o.oldestUndelivered.eventId) + '</code></p><p>State: <strong>' +
      esc(o.oldestUndelivered.readiness) + '</strong></p>' :
      '<p class="subtle">No unfinished outbox event reported.</p>') + '</div>' :
      empty("◈", "Operator access required",
        "Connect an operator JWT to inspect recovery state.",
        button("Connect credentials", "session"))) +
    (o ? '<div class="section-head"><h2>Manual review queue</h2></div><section class="panel">' +
      (queue || '<p class="subtle">No supervised operations currently need review.</p>') +
      '</section>' : "");
}
function render() {
  document.querySelectorAll("[data-nav]").forEach(node => {
    const active = node.dataset.nav === state.page;
    node.classList.toggle("active", active);
    if (active) node.setAttribute("aria-current", "page");
    else node.removeAttribute("aria-current");
  });
  const total = (state.cart?.items || []).reduce((s, x) => s + Number(x.quantity || 0), 0);
  countNode.textContent = String(total);
  let content;
  if (state.loading) content = '<div class="loading" role="status">Loading from the backend…</div>';
  else {
    content = {
      shop, saved, cart, orders, detail, orderDetail, ops,
    }[state.page]?.() || shop();
    if (state.error) content = errorBox(state.error) + content;
  }
  app.innerHTML = content;
}
async function load(page = state.page) {
  state.loading = true;
  state.error = "";
  render();
  try {
    if (page === "shop") {
      const response = (await api.catalog({ query: state.query, category: state.category,
        sort: state.sort, page: state.pageIndex })).data;
      state.products = Array.isArray(response?.products) ? response.products : [];
      state.totalElements = Number(response?.totalElements || 0);
      state.totalPages = Number(response?.totalPages || 0);
    } else if (page === "saved") {
      const response = (await api.wishlist()).data;
      state.saved = Array.isArray(response?.items) ? response.items : [];
    } else if (page === "cart") {
      state.cart = (await api.cart()).data;
    } else if (page === "orders") {
      const response = (await api.orders()).data;
      state.orders = Array.isArray(response?.orders) ? response.orders : [];
    } else if (page === "detail") {
      state.detail = (await api.product(state.detailId)).data;
    } else if (page === "orderDetail") {
      state.detail = (await api.order(state.orderId)).data;
    } else if (page === "ops") {
      const [overview, queue] = await Promise.all([api.overview(), api.reviewQueue()]);
      state.overview = overview.data;
      state.reviewQueue = Array.isArray(queue.data) ? queue.data : [];
    }
  } catch (error) {
    state.error = error instanceof ApiError ? error.message : "Unexpected frontend error.";
    if (error?.status === 401 || error?.status === 403) showToast(state.error, true);
  } finally {
    state.loading = false;
    render();
  }
}
function navigate(page) {
  if (!["shop", "saved", "cart", "orders", "ops"].includes(page)) page = "shop";
  if (location.hash !== "#" + page) location.hash = "#" + page;
  else { state.page = page; load(page); }
}
async function doAction(action, buttonNode) {
  const id = Number(buttonNode.dataset.id);
  if (action === "session") {
    document.querySelector("#access-token").value = state.token;
    document.querySelector("#operator-token").value = state.operatorToken;
    sessionDialog.showModal();
    return;
  }
  if (action === "save-session") {
    state.token = document.querySelector("#access-token").value.trim();
    state.operatorToken = document.querySelector("#operator-token").value.trim();
    state.cart = null;
    state.checkoutKey = null;
    sessionDialog.close();
    showToast(state.token ? "Session connected. Tokens are held in memory only." :
      "Session disconnected.");
    await load();
    return;
  }
  if (action === "clear-session") {
    state.token = ""; state.operatorToken = ""; state.cart = null;
    state.orders = []; state.saved = []; state.checkoutKey = null;
    document.querySelector("#access-token").value = "";
    document.querySelector("#operator-token").value = "";
    sessionDialog.close();
    showToast("Credentials cleared from page memory.");
    await load();
    return;
  }
  if (action === "create") { createDialog.showModal(); return; }
  if (action === "close-create") { createDialog.close(); return; }
  if (action === "navigate") { navigate(buttonNode.dataset.page); return; }
  if (action === "focus-search") {
    document.querySelector("#search-input")?.focus();
    return;
  }
  if (action === "prev" || action === "next") {
    state.pageIndex = Math.max(0, state.pageIndex + (action === "next" ? 1 : -1));
    await load("shop");
    return;
  }
  if (action === "detail" && validId(id)) {
    state.detailId = id; state.page = "detail"; await load("detail"); return;
  }
  if (action === "order" && buttonNode.dataset.order) {
    state.orderId = buttonNode.dataset.order; state.page = "orderDetail";
    await load("orderDetail"); return;
  }
  if (["add", "save", "unsave", "remove", "increment", "decrement"].includes(action)
      && validId(id)) {
    if (action === "add") {
      const cart = (await api.cart()).data;
      const current = (cart?.items || []).find(x => Number(x.productId) === id);
      await api.setCartItem(id, Math.min(99, Number(current?.quantity || 0) + 1));
      state.checkoutKey = null;
      showToast("Added to your bag.");
      state.cart = (await api.cart()).data;
      render();
    } else if (action === "save") {
      await api.save(id); showToast("Saved to wishlist.");
      if (state.page === "shop" || state.page === "detail") render();
    } else if (action === "unsave") {
      await api.unsave(id); showToast("Removed from wishlist.");
      await load();
    } else {
      const newQuantity = action === "remove" ? 0 :
        Number(buttonNode.dataset.quantity) + (action === "increment" ? 1 : -1);
      if (newQuantity < 1) await api.removeCartItem(id);
      else await api.setCartItem(id, Math.min(newQuantity, 99));
      state.checkoutKey = null;
      await load("cart");
    }
    return;
  }
  if (action === "checkout") {
    if (!state.checkoutKey) {
      state.checkoutKey = "cartlane-" + crypto.randomUUID();
    }
    const receipt = (await api.checkout(state.checkoutKey)).data;
    if (!receipt?.orderId) throw new ApiError("Checkout did not return an order ID.");
    state.checkoutKey = null;
    showToast("Mock order reserved. No payment was charged.");
    navigate("orders");
    return;
  }
}
document.addEventListener("click", async event => {
  const node = event.target.closest("[data-action]");
  if (!node || node.disabled) return;
  event.preventDefault();
  const action = node.dataset.action;
  node.disabled = true;
  try { await doAction(action, node); }
  catch (error) { showToast(error instanceof ApiError ? error.message : "Unable to complete action.", true); }
  finally { node.disabled = false; }
});
document.querySelector("#catalog-search");
document.addEventListener("submit", async event => {
  if (event.target.id === "catalog-search") {
    event.preventDefault();
    const values = new FormData(event.target);
    state.query = String(values.get("query") || "").trim().slice(0, 100);
    state.category = String(values.get("category") || "");
    state.sort = String(values.get("sort") || "productIdDesc");
    state.pageIndex = 0;
    await load("shop");
  }
  if (event.target.id === "create-form") {
    event.preventDefault();
    const values = new FormData(event.target);
    const name = String(values.get("name") || "").trim();
    const price = Number(values.get("price"));
    const stock = Number(values.get("stock"));
    if (name.length < 3 || !Number.isFinite(price) || price < 1 ||
      !Number.isInteger(stock) || stock < 0 || stock > 9999) {
      showToast("Enter a valid name, price, and stock.", true); return;
    }
    const productId = 100000000 + crypto.getRandomValues(new Uint32Array(1))[0] % 1800000000;
    const body = { productId, name, weight: 1,
      sku: "CARTLANE-DEMO-" + productId, description: "Synthetic demo catalog item",
      category: String(values.get("category") || "electronics"),
      price, currency: "INR", stockQuantity: stock, imageUrls: [],
      recommendations: [], reviews: [] };
    const submit = event.target.querySelector("[type=submit]");
    submit.disabled = true;
    try {
      const result = await api.createProduct(body);
      if (result.status !== 202) throw new ApiError("Expected durable intent response (202).");
      createDialog.close();
      event.target.reset();
      showToast("Event accepted (202). Consumers may take a moment; refresh catalog shortly.");
      state.page = "shop"; state.query = name; state.pageIndex = 0;
      navigate("shop");
      await load("shop");
    } catch (error) {
      showToast(error instanceof ApiError ? error.message : "Unable to publish product.", true);
    } finally { submit.disabled = false; }
  }
});
window.addEventListener("hashchange", () => {
  state.page = (location.hash || "#shop").slice(1);
  if (!["shop", "saved", "cart", "orders", "ops"].includes(state.page)) state.page = "shop";
  load(state.page);
});
(async () => {
  state.page = (location.hash || "#shop").slice(1);
  if (!["shop", "saved", "cart", "orders", "ops"].includes(state.page)) state.page = "shop";
  try {
    const health = await api.health();
    const online = health.data?.status === "UP";
    const status = document.querySelector("#api-status");
    status.className = "api-indicator " + (online ? "online" : "offline");
    status.textContent = online ? "Backend online" : "Backend unavailable";
  } catch {
    const status = document.querySelector("#api-status");
    status.className = "api-indicator offline";
    status.textContent = "Backend offline";
  }
  await load(state.page);
})();
