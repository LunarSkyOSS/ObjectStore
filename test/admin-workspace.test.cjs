const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

async function staleAccessCheck() {
  const base = "http://localhost/_objectstore/admin";
  const location = new URL(base + "/audit");
  const events = new Map();
  const requests = [];
  let content;
  let replacements = 0;
  const makeContent = () => ({
    isConnected: true,
    querySelector: () => null,
    querySelectorAll: () => [],
    replaceWith(next) {
      this.isConnected = false;
      content = next;
      replacements++;
    }
  });
  content = makeContent();
  const initial = content;
  class Form {
    action = base + "/action/access-check";
    get isConnected() { return initial.isConnected; }
    closest() { return initial; }
  }
  class Link {
    href = base + "/buckets";
    target = "";
    hasAttribute() { return false; }
  }
  const document = {
    body: { dataset: {} },
    getElementById: () => null,
    querySelector: selector => selector === ".workspace-content" ? content : null,
    querySelectorAll: () => [],
    addEventListener: (type, callback) => events.set(type, callback),
    importNode: node => node
  };
  const history = {
    state: null,
    replaceState(state, title, url) {
      this.state = state;
      location.href = new URL(url, location).href;
    },
    pushState(state, title, url) { this.replaceState(state, title, url); }
  };
  const context = {
    document, history, location, URL, URLSearchParams, AbortController, AbortSignal,
    HTMLFormElement: Form, HTMLAnchorElement: Link,
    FormData: class extends Map { constructor() { super(); } },
    DOMParser: class {
      parseFromString() {
        return { title: "ObjectStore admin", querySelector: () => makeContent() };
      }
    },
    localStorage: { getItem: () => null },
    matchMedia: () => ({ matches: false, addEventListener() {} }),
    setTimeout, clearTimeout, console,
    window: { scrollY: 0, scrollTo() {}, addEventListener() {} },
    fetch(url, options) {
      if (String(url).endsWith("/branding.json")) return Promise.resolve({ ok: false });
      return new Promise(resolve => requests.push({ url: String(url), options, resolve }));
    }
  };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, "../admin-ui/workspace.js"), "utf8"), context);
  events.get("submit")({ target: new Form(), defaultPrevented: false, preventDefault() {} });
  assert.equal(requests.length, 1);
  events.get("click")({ defaultPrevented: false, button: 0,
    composedPath: () => [new Link()], preventDefault() {} });
  assert.equal(requests.length, 2);
  assert.equal(requests[0].options.signal.aborted, true, "Navigation must cancel the old access check");

  const respond = (request, url) => request.resolve({
    url, ok: true, headers: { get: () => "text/html" }, text: async () => "page"
  });
  respond(requests[1], base + "/buckets");
  await new Promise(setImmediate);
  respond(requests[0], base + "/audit");
  await new Promise(setImmediate);
  assert.equal(location.pathname, "/_objectstore/admin/buckets");
  assert.equal(replacements, 1, "Late access-check response replaced the newly opened page");
}

staleAccessCheck().then(() => console.log("Admin workspace tests passed: stale access-check response"))
  .catch(error => { console.error(error); process.exitCode = 1; });
