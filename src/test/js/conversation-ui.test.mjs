import test from "node:test";
import assert from "node:assert/strict";
import {setImmediate} from "node:timers";
import {readFileSync} from "node:fs";
import vm from "node:vm";
import {URL} from "node:url";
import {createConversationClient, isConversation, isConversationMessage} from "../../main/resources/static/conversation-ui.mjs";

const key = "11111111-1111-4111-8111-111111111111";
const message = (id = 2, body = "reply", correction = null) => ({id, type: "OFFICIAL_REPLY", body, correctsMessageId: correction, createdAt: "2026-10-23T01:00:00Z"});
const page = (id = 1, canReply = true) => ({ticket: {id, title: "synthetic ticket", status: "OPEN"}, capabilities: {canReply},
    messages: [{...message(1, "inquiry"), type: "INQUIRY"}], page: {nextAfterId: null, hasMore: false}});
const response = (body, status = 200) => new Response(JSON.stringify(body), {status, headers: {"Content-Type": "application/json"}});
const csrf = () => response({headerName: "X-CSRF-TOKEN", token: "synthetic-test-token"});
function harness(fetchImpl, factory = () => key) {
    const states = [], busy = [];
    return {states, busy, client: createConversationClient({fetchImpl, newRequestId: factory,
        view: {render: (state) => states.push(state), setPosting: (value) => busy.push(value)}})};
}
function deferred() {
    let resolve;
    const promise = new Promise((done) => { resolve = done; });
    return {promise, resolve};
}

test("conversation contract rejects wrong ticket, ordering, correction shape and unsafe IDs", () => {
    assert.equal(isConversation(page(), 1), true);
    assert.equal(isConversation(page(), 2), false);
    assert.equal(isConversation({...page(), extra: true}, 1), false);
    assert.equal(isConversation({...page(), messages: [message(2), message(1)]}, 1), false);
    assert.equal(isConversation({...page(), page: {hasMore: true, nextAfterId: null}}, 1), false);
    assert.equal(isConversationMessage({...message(), correctsMessageId: 2}), false);
    assert.equal(isConversationMessage({...message(), type: "INQUIRY", correctsMessageId: 1}), false);
    assert.equal(isConversationMessage({...message(), id: Number.MAX_SAFE_INTEGER + 1}), false);
    assert.equal(isConversationMessage({...message(), createdAt: "bad date"}), false);
});

test("HTTP 200 with valid conversation is a read, not a new reply", async () => {
    const h = harness(async () => response(page(1, false)));
    await h.client.read(1);
    assert.equal(h.states.at(-1).kind, "conversation");
    await h.client.reply("not authorized");
    assert.equal(h.states.at(-1).kind, "forbidden");
});

test("invalid ID and input prevent any unnecessary request", async () => {
    let calls = 0;
    const h = harness(async () => { calls++; return response(page()); });
    await h.client.read(0);
    assert.equal(calls, 0);
    await h.client.read(1);
    for (const body of ["", "  ", "😀".repeat(2001)]) await h.client.reply(body);
    await h.client.reply("body", 0);
    assert.equal(calls, 1);
});

test("latest read wins even if old fetch ignores abort", async () => {
    const old = deferred();
    const h = harness((url) => url.includes("/1/") ? old.promise : Promise.resolve(response(page(2))));
    const first = h.client.read(1);
    await h.client.read(2);
    old.resolve(response(page(1)));
    await first;
    assert.equal(h.states.at(-1).conversation.ticket.id, 2);
});

test("clearing selection invalidates pending reads and authorization", async () => {
    const old = deferred();
    const h = harness(() => old.promise);
    const first = h.client.read(1);
    h.client.clear();
    old.resolve(response(page())); await first;
    assert.equal(h.states.at(-1).kind, "selection-changed");
    await h.client.reply("not selected");
    assert.equal(h.states.at(-1).kind, "forbidden");
});

test("CSRF and same-origin credentials precede POST; success refreshes", async () => {
    const calls = [];
    const h = harness(async (url, options) => {
        calls.push({url, options});
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") return response(message(2, "  <strong>hello</strong>  "), 201);
        return response(page());
    });
    await h.client.read(1);
    await h.client.reply("  <strong>hello</strong>  ");
    const post = calls.find((call) => call.options.method === "POST").options;
    assert.equal(post.credentials, "same-origin");
    assert.equal(post.headers["X-CSRF-TOKEN"], "synthetic-test-token");
    assert.deepEqual(JSON.parse(post.body), {body: "  <strong>hello</strong>  ", clientRequestId: key, correctsMessageId: null});
    assert.equal(h.states.at(-1).kind, "reply-saved");
    assert.deepEqual(h.busy, [false]);
});

test("unknown outcome retries only explicitly and reuses original request ID", async () => {
    let posts = 0, keys = 0;
    const submitted = [];
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") {
            submitted.push(JSON.parse(options.body)); posts++;
            if (posts === 1) throw new Error("synthetic connection loss");
            return response(message(), 200);
        }
        return response(page());
    }, () => {keys++; return key;});
    await h.client.read(1); await h.client.reply("reply");
    assert.equal(posts, 1); assert.equal(h.states.at(-1).kind, "unknown-outcome");
    await h.client.reply("changed");
    assert.equal(posts, 1); assert.equal(h.states.at(-1).kind, "unconfirmed-submission");
    await h.client.reply("reply");
    assert.equal(posts, 2); assert.equal(keys, 1); assert.deepEqual(submitted[0], submitted[1]);
    assert.equal(h.states.at(-1).kind, "reply-saved");
});

test("unconfirmed correction target cannot be silently changed", async () => {
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        return options.method === "POST" ? response({}, 500) : response(page());
    });
    await h.client.read(1); await h.client.reply("reply", 5); await h.client.reply("reply", 6);
    assert.equal(h.states.at(-1).kind, "unconfirmed-submission");
});

test("double submit cannot issue a second POST while first is in flight", async () => {
    const pending = deferred(); let posts = 0;
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") { posts++; return pending.promise; }
        return response(page());
    });
    await h.client.read(1);
    const first = h.client.reply("reply");
    await h.client.reply("reply");
    await new Promise((done) => setImmediate(done));
    pending.resolve(response(message(), 201)); await first;
    assert.equal(posts, 1);
});

test("late POST result cannot replace a different selected ticket", async () => {
    const pending = deferred();
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") return pending.promise;
        return response(page(url.includes("/2/") ? 2 : 1));
    });
    await h.client.read(1);
    const first = h.client.reply("reply");
    await new Promise((done) => setImmediate(done));
    await h.client.read(2);
    pending.resolve(response(message(), 201)); await first;
    assert.equal(h.states.at(-1).conversation.ticket.id, 2);
});

test("CSRF rejection prevents POST", async () => {
    let posts = 0;
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return response({}, 401);
        if (options.method === "POST") posts++;
        return response(page());
    });
    await h.client.read(1); await h.client.reply("reply");
    assert.equal(posts, 0); assert.equal(h.states.at(-1).kind, "login-required");
});

test("known validation rejection permits a corrected new submission", async () => {
    let posts = 0, keys = 0;
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") return ++posts === 1 ? response({}, 400) : response(message(2, "corrected"), 201);
        return response(page());
    }, () => { keys++; return key; });
    await h.client.read(1); await h.client.reply("reply"); await h.client.reply("corrected");
    assert.equal(keys, 2); assert.equal(h.states.at(-1).kind, "reply-saved");
});

test("leaving the page clears conversation and draft before a history-cache restoration", () => {
    const handlers = new Map(), nodes = new Map();
    let clears = 0;
    const source = readFileSync(new URL("../../main/resources/static/conversation-page.mjs", import.meta.url), "utf8");
    const moduleBody = source.replace(/^import \{createConversationClient\} from "\.\/conversation-ui\.mjs";\r?\n/u, "");
    const node = (selector) => {
        if (!nodes.has(selector)) nodes.set(selector, {
            value: "old draft", hidden: false, textContent: "old conversation", children: ["old message"],
            addEventListener() {}, replaceChildren(...children) { this.children = children; }
        });
        return nodes.get(selector);
    };
    vm.runInNewContext(moduleBody, {
        createConversationClient: () => ({clear() { clears++; }}),
        document: {querySelector: node},
        window: {fetch() {}, crypto: {randomUUID() {}}, addEventListener: (name, handler) => handlers.set(name, handler)},
        Option: class { constructor(label, value) { this.label = label; this.value = value; } }
    });
    handlers.get("pagehide")();
    assert.equal(clears, 1);
    assert.equal(node("#reply-body").value, "");
    assert.equal(node("#conversation-ticket-id").value, "");
    assert.equal(node("#conversation-messages").children.length, 0);
    assert.equal(node("#official-reply").hidden, true);
});
