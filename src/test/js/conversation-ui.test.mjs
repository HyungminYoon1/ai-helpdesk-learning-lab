import test from "node:test";
import assert from "node:assert/strict";
import {setImmediate} from "node:timers";
import {readFileSync} from "node:fs";
import vm from "node:vm";
import {URL} from "node:url";
import {createConversationClient, isConversation, isConversationMessage} from "../../main/resources/static/conversation-ui.mjs";

const key = "11111111-1111-4111-8111-111111111111";
const message = (id = 2, body = "reply", correction = null) => ({id, type: "OFFICIAL_REPLY", body, correctsMessageId: correction, createdAt: "2026-10-23T01:00:00Z"});
const page = (id = 1, canReply = true) => ({ticket: {id, title: "synthetic ticket", status: "OPEN"}, capabilities: {canReply, canFollowUp: !canReply, canViewConfirmations: canReply, expectedAssignmentVersion: canReply ? 1 : null},
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
    assert.equal(isConversation({...page(), capabilities: {canReply: false, expectedAssignmentVersion: null}}, 1), false);
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

test("customer followups are valid conversation messages but cannot correct an official reply", () => {
    const followup = {...message(2, "customer detail"), type: "CUSTOMER_FOLLOWUP"};
    assert.equal(isConversationMessage(followup), true);
    assert.equal(isConversationMessage({...followup, correctsMessageId: 1}), false);
    const data = page(1, false);
    data.messages.push(followup);
    assert.equal(isConversation(data, 1), true);
});

test("a customer followup returned by the reply endpoint cannot be accepted as a saved official reply", async () => {
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") return response({...message(), type: "CUSTOMER_FOLLOWUP"}, 200);
        return response(page());
    });
    await h.client.read(1);
    await h.client.reply("reply");
    assert.equal(h.states.at(-1).kind, "unknown-outcome");
    assert.equal(h.states.some((state) => state.kind === "reply-saved"), false);
});

test("customer followup uses a separate capability and sends only body and submission UUID", async () => {
    const sent = [];
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") {
            sent.push({url, options});
            return response({...message(2, "  detail\nunchanged  "), type: "CUSTOMER_FOLLOWUP"}, 201);
        }
        const data = page(1, false); data.ticket.status = "RESOLVED"; return response(data);
    });
    await h.client.read(1); await h.client.followUp("  detail\nunchanged  ");
    assert.equal(h.states.at(-1).kind, "followup-saved");
    assert.equal(sent[0].url, "/api/tickets/1/customer-messages");
    assert.deepEqual(JSON.parse(sent[0].options.body), {body: "  detail\nunchanged  ", clientRequestId: key});
    assert.equal(sent[0].options.credentials, "same-origin");
    assert.equal(sent[0].options.headers["X-CSRF-TOKEN"], "synthetic-test-token");
});

test("staff reply capability cannot grant customer followup capability", async () => {
    let calls = 0;
    const h = harness(async () => {calls++; return response(page());});
    await h.client.read(1); await h.client.followUp("not owner");
    assert.equal(h.states.at(-1).kind, "forbidden"); assert.equal(calls, 1);
});

test("unknown customer submission preserves key and kind and is retried only explicitly", async () => {
    const posts = []; let keys = 0;
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") {
            posts.push({url, body: options.body});
            if (posts.length === 1) throw new Error("lost response");
            return response({...message(2, "detail"), type: "CUSTOMER_FOLLOWUP"}, 200);
        }
        const data = page(); data.capabilities.canFollowUp = true; return response(data);
    }, () => {keys++; return key;});
    await h.client.read(1); await h.client.followUp("detail");
    assert.equal(h.states.at(-1).kind, "unknown-outcome");
    await h.client.reply("detail"); assert.equal(h.states.at(-1).kind, "unconfirmed-submission");
    await h.client.followUp("changed"); assert.equal(posts.length, 1);
    await h.client.followUp("detail"); assert.equal(posts.length, 2); assert.equal(keys, 1);
    assert.deepEqual(posts[0], posts[1]); assert.equal(h.states.at(-1).kind, "followup-saved");
});

test("saved customer submission with a failed refresh needs only GET recovery", async () => {
    let reads = 0, posts = 0;
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") {posts++; return response({...message(2, "detail"), type: "CUSTOMER_FOLLOWUP"}, 201);}
        return ++reads === 2 ? response({}, 503) : response(page(1, false));
    });
    await h.client.read(1); await h.client.followUp("detail");
    assert.equal(h.states.at(-1).kind, "followup-saved-refresh-failed");
    await h.client.read(1); assert.equal(posts, 1); assert.equal(reads, 3);
});

test("a reply returned by customer endpoint remains unconfirmed", async () => {
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        return options.method === "POST" ? response(message(2, "detail"), 201) : response(page(1, false));
    });
    await h.client.read(1); await h.client.followUp("detail"); assert.equal(h.states.at(-1).kind, "unknown-outcome");
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
    assert.deepEqual(JSON.parse(post.body), {body: "  <strong>hello</strong>  ", clientRequestId: key, correctsMessageId: null, expectedAssignmentVersion: 1});
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

test("an unsent stale draft needs an explicit new draft and GET before a new version is used", async () => {
    let version = 1, keys = 0; const posts = [], calls = [];
    const h = harness(async (url, options) => {
        calls.push({url, method: options.method});
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") { posts.push(JSON.parse(options.body)); return response(message(2, "new draft"), 201); }
        const data = page(); data.capabilities.expectedAssignmentVersion = version; return response(data);
    }, () => { keys++; return key; });
    await h.client.read(1); version = 3; await h.client.read(1); await h.client.reply("old draft");
    assert.equal(h.states.at(-1).kind, "reply-review-required");
    assert.equal(h.states.at(-1).replyDraftVersion, 1);
    assert.equal(h.states.at(-1).replyCurrentVersion, 3);
    assert.equal(h.states.at(-1).canReviewReply, true);
    assert.equal(posts.length, 0); assert.equal(keys, 0);
    const before = calls.length;
    assert.equal(await h.client.reviewReply(), true);
    assert.deepEqual(calls.slice(before).map((call) => call.method), ["GET"]);
    assert.equal(h.states.some((state) => state.kind === "reply-draft-reset"), true);
    assert.equal(h.states.at(-1).replyDraftVersion, 3);
    assert.equal(keys, 0);
    await h.client.reply("new draft");
    assert.equal(posts.length, 1); assert.equal(keys, 1);
    assert.equal(posts[0].expectedAssignmentVersion, 3);
});

test("only a typed reply version rejection permits explicit reset of a submitted draft", async () => {
    let version = 1, keys = 0; const posts = [], reads = [];
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") {
            posts.push(JSON.parse(options.body));
            return posts.length === 1 ? response({code: "ASSIGNMENT_VERSION_CONFLICT"}, 409) : response(message(3, "reviewed"), 201);
        }
        reads.push(url); const data = page(); data.capabilities.expectedAssignmentVersion = version; return response(data);
    }, () => `11111111-1111-4111-8111-${String(++keys).padStart(12, "0")}`);
    await h.client.read(1); version = 3; await h.client.reply("old draft", 2);
    assert.equal(h.states.at(-1).kind, "reply-version-conflict");
    assert.equal(h.states.at(-1).canReviewReply, true);
    assert.equal(h.states.at(-1).canReconfirm, false);
    await h.client.read(1); await h.client.reply("old draft", 2);
    assert.equal(posts.length, 1); assert.equal(keys, 1);
    const before = reads.length;
    assert.equal(await h.client.reviewReply(), true);
    assert.equal(reads.length, before + 1); assert.equal(posts.length, 1); assert.equal(keys, 1);
    await h.client.reply("reviewed");
    assert.equal(posts[1].expectedAssignmentVersion, 3);
    assert.equal(posts[1].correctsMessageId, null);
    assert.notEqual(posts[0].clientRequestId, posts[1].clientRequestId);
    assert.equal(h.states.at(-1).kind, "reply-saved");
});

for (const [name, status, problem] of [
    ["payload conflict", 409, {code: "REPLY_REQUEST_CONFLICT"}],
    ["unknown code", 409, {code: "OTHER"}],
    ["missing code", 409, {}],
    ["null body", 409, null],
    ["array body", 409, [{code: "ASSIGNMENT_VERSION_CONFLICT"}]],
    ["invalid JSON", 409, "invalid-json"],
    ["server failure", 500, {code: "ASSIGNMENT_VERSION_CONFLICT"}],
    ["non-assignee", 403, {code: "ASSIGNMENT_VERSION_CONFLICT"}],
    ["out-of-scope", 404, {code: "ASSIGNMENT_VERSION_CONFLICT"}]
]) test(`a prior unknown reply cannot be reset on ${name}`, async () => {
    let version = 1, keys = 0; const posts = [];
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") {
            posts.push(options.body);
            if (posts.length === 1) throw new Error("synthetic response loss");
            return problem === "invalid-json" ? new Response("invalid", {status}) : response(problem, status);
        }
        const data = page(); data.capabilities.expectedAssignmentVersion = version; return response(data);
    }, () => { keys++; return key; });
    await h.client.read(1); await h.client.reply("reply", 2); version = 3;
    await h.client.read(1);
    assert.equal(h.states.at(-1).canReviewReply, false);
    assert.equal(h.states.at(-1).canReconfirm, true);
    assert.equal(await h.client.reviewReply(), false);
    await h.client.reply("reply", 2);
    assert.equal(h.states.at(-1).canReviewReply, false);
    assert.equal(await h.client.reviewReply(), false);
    await h.client.reply("reply", 2);
    assert.equal(keys, 1); assert.equal(posts.length, 3);
    assert.equal(posts[0], posts[1]); assert.equal(posts[1], posts[2]);
});

for (const status of [401, 403, 404, 503]) test(`a new draft cannot POST after its review GET returns ${status}`, async () => {
    let version = 1, review = false, posts = 0;
    const h = harness(async (_url, options) => {
        if (options.method === "POST") posts++;
        if (review) return response({}, status);
        const data = page(); data.capabilities.expectedAssignmentVersion = version; return response(data);
    });
    await h.client.read(1); version = 3; await h.client.read(1); review = true;
    assert.equal(await h.client.reviewReply(), false);
    await h.client.reply("new draft");
    assert.equal(posts, 0); assert.equal(h.states.at(-1).kind, "forbidden");
});

test("a pending customer followup also blocks resetting a stale reply draft", async () => {
    let version = 1; const posts = [];
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") { posts.push(options.body); throw new Error("synthetic loss"); }
        const data = page(); data.capabilities.canFollowUp = true; data.capabilities.expectedAssignmentVersion = version; return response(data);
    });
    await h.client.read(1); await h.client.followUp("customer detail"); version = 3; await h.client.read(1);
    assert.equal(await h.client.reviewReply(), false);
    await h.client.followUp("customer detail");
    assert.equal(posts.length, 2); assert.equal(posts[0], posts[1]);
});

test("review is blocked while POST is in flight and a late review GET cannot replace another ticket", async () => {
    const post = deferred(), read = deferred(); let version = 1, reviewing = false, posts = 0;
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") { posts++; return post.promise; }
        if (reviewing && url.includes("/1/")) return read.promise;
        const data = page(url.includes("/2/") ? 2 : 1); data.capabilities.expectedAssignmentVersion = version; return response(data);
    });
    await h.client.read(1); const first = h.client.reply("reply");
    await new Promise((done) => setImmediate(done));
    assert.equal(await h.client.reviewReply(), false);
    post.resolve(response({code: "ASSIGNMENT_VERSION_CONFLICT"}, 409)); await first;
    reviewing = true; version = 3; const refresh = h.client.reviewReply();
    await h.client.read(2); read.resolve(response(page())); await refresh;
    assert.equal(h.states.at(-1).conversation.ticket.id, 2); assert.equal(posts, 1);
});

test("unknown saved reply can be reconfirmed after handoff without changing version", async () => {
    let canReply = true; const posts = [];
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") { posts.push(JSON.parse(options.body)); throw new Error("synthetic loss"); }
        return response(page(1, canReply));
    });
    await h.client.read(1); await h.client.reply("reply"); canReply = false; await h.client.read(1); await h.client.reply("reply");
    assert.equal(posts.length, 2); assert.deepEqual(posts[0], posts[1]);
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

function pageHarness(fetchImpl, factory = () => key) {
    const nodes = new Map(), handlers = new Map();
    const node = (selector) => {
        if (!nodes.has(selector)) nodes.set(selector, {
            value: "", hidden: false, disabled: false, readOnly: false, textContent: "", children: [], handlers: new Map(),
            get options() { return this.children; },
            addEventListener(name, handler) { this.handlers.set(name, handler); },
            replaceChildren(...children) { this.children = children; },
            add(option) { this.children.push(option); },
            append(...children) { this.children.push(...children); }
        });
        return nodes.get(selector);
    };
    let client;
    const source = readFileSync(new URL("../../main/resources/static/conversation-page.mjs", import.meta.url), "utf8");
    vm.runInNewContext(source.replace(/^import .*;\r?\n/gmu, ""), {
        createConversationClient: (options) => { client = createConversationClient(options); return client; },
        attachCustomerConfirmations: () => ({observe() {}, clear() {}}),
        document: {querySelector: node, createElement: () => ({append() {}})},
        window: {fetch: fetchImpl, crypto: {randomUUID: factory}, addEventListener: (name, handler) => handlers.set(name, handler)},
        Option: class { constructor(label, value) { this.label = label; this.value = value; } }
    });
    return {client, node, handlers};
}

test("the page retains a rejected draft until explicit review and clears body and correction before GET", async () => {
    let version = 1, posts = 0;
    const h = pageHarness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") { posts++; return response({code: "ASSIGNMENT_VERSION_CONFLICT"}, 409); }
        const data = page(); data.capabilities.expectedAssignmentVersion = version;
        data.messages.push(message()); return response(data);
    });
    await h.client.read(1);
    h.node("#reply-body").value = "old draft"; h.node("#reply-correction").value = "2";
    version = 3; await h.client.reply("old draft", 2);
    assert.equal(h.node("#reply-body").value, "old draft");
    assert.equal(h.node("#reply-correction").value, "2");
    assert.equal(h.node("#reply-send").disabled, true);
    assert.equal(h.node("#reply-review").hidden, false);
    await h.client.read(1);
    assert.equal(h.node("#reply-body").value, "old draft");
    assert.equal(h.node("#reply-correction").value, "2");
    assert.equal(h.node("#reply-send").disabled, true);
    h.node("#reply-review").handlers.get("click")();
    assert.equal(h.node("#reply-body").value, "");
    assert.equal(h.node("#reply-correction").value, "");
    await new Promise((done) => setImmediate(done));
    assert.equal(h.node("#reply-review").hidden, true);
    assert.equal(h.node("#reply-body").readOnly, false);
    assert.equal(posts, 1);
});

test("refresh preserves an unknown correction even when its target is not in the first page", async () => {
    let reads = 0; const posts = [];
    const h = pageHarness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") {
            posts.push(JSON.parse(options.body));
            if (posts.length === 1) throw new Error("synthetic response loss");
            return response(message(3, "correction", 2), 200);
        }
        const data = page();
        if (++reads === 1) data.messages.push(message());
        else data.capabilities.expectedAssignmentVersion = 3;
        return response(data);
    });
    await h.client.read(1);
    h.node("#reply-body").value = "correction"; h.node("#reply-correction").value = "2";
    await h.client.reply("correction", 2); await h.client.read(1);
    assert.equal(h.node("#reply-correction").value, "2");
    assert.equal(h.node("#reply-body").value, "correction");
    assert.equal(h.node("#reply-body").readOnly, true);
    assert.equal(h.node("#reply-review").hidden, true);
    assert.equal(h.node("#reply-send").disabled, false);
    await h.client.reply(h.node("#reply-body").value, Number(h.node("#reply-correction").value));
    assert.deepEqual(posts[0], posts[1]);
    assert.equal(h.node("#reply-body").value, "");
    assert.equal(h.node("#reply-correction").value, "");
});

test("saving a reply clears only its draft and retains loaded correction choices", async () => {
    let saved = false;
    const h = pageHarness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") { saved = true; return response(message(3, "new reply"), 201); }
        const data = page(); data.messages.push(message());
        if (saved) data.messages.push(message(3, "new reply"));
        return response(data);
    });
    await h.client.read(1); h.node("#reply-body").value = "new reply";
    await h.client.reply("new reply");
    assert.equal(h.node("#reply-body").value, "");
    assert.equal(h.node("#reply-correction").value, "");
    assert.deepEqual(h.node("#reply-correction").options.map((option) => option.value), ["", "2", "3"]);
});

test("leaving the page clears conversation and draft before a history-cache restoration", () => {
    const handlers = new Map(), nodes = new Map();
    let clears = 0;
    const source = readFileSync(new URL("../../main/resources/static/conversation-page.mjs", import.meta.url), "utf8");
    const moduleBody = source.replace(/^import .*;\r?\n/gmu, "");
    const node = (selector) => {
        if (!nodes.has(selector)) nodes.set(selector, {
            value: "old draft", hidden: false, textContent: "old conversation", children: ["old message"],
            addEventListener() {}, replaceChildren(...children) { this.children = children; }
        });
        return nodes.get(selector);
    };
    vm.runInNewContext(moduleBody, {
        createConversationClient: () => ({clear() { clears++; }}),
        attachCustomerConfirmations: () => ({observe() {}, clear() {}}),
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
    assert.equal(node("#customer-followup").hidden, true);
    assert.equal(node("#followup-body").value, "");
});
