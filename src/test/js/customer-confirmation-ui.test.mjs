import test from "node:test";
import assert from "node:assert/strict";
import {setImmediate as nextTick} from "node:timers/promises";
import {createCustomerConfirmationClient, isConfirmationState} from "../../main/resources/static/customer-confirmation-ui.mjs";
import {attachCustomerConfirmations} from "../../main/resources/static/customer-confirmation-page.mjs";

const key = "11111111-1111-4111-8111-111111111111";
const current = (extra = {}) => ({assignmentVersion: 1, effectiveThroughMessageId: null, latestCustomerMessageId: 30,
    needsConfirmation: true, canConfirm: true, ...extra});
const response = (body, status = 200) => new Response(JSON.stringify(body), {status});
const csrf = () => response({headerName: "X-CSRF-TOKEN", token: "synthetic-token"});
const change = (extra = {}) => ({confirmation: {id: 1, assignmentVersion: 1, throughMessageId: 30, recordedAt: "2026-11-04T01:00:00Z"},
    currentConfirmation: current({effectiveThroughMessageId: 30, needsConfirmation: false}), ...extra});
function harness(fetchImpl, newRequestId = () => key) {
    const states = [], busy = [];
    return {states, busy, client: createCustomerConfirmationClient({fetchImpl, newRequestId,
        view: {render: (state) => states.push(state), setBusy: (value) => busy.push(value)}})};
}
function deferred() { let resolve; const promise = new Promise((done) => { resolve = done; }); return {promise, resolve}; }

test("current confirmation distinguishes absent records, absent customers and non-assignees", () => {
    assert.equal(isConfirmationState(current()), true);
    assert.equal(isConfirmationState(current({assignmentVersion: 0, canConfirm: false})), true);
    assert.equal(isConfirmationState(current({latestCustomerMessageId: null, needsConfirmation: false, canConfirm: false})), true);
    for (const extra of [{assignmentVersion: 0}, {effectiveThroughMessageId: 31}, {effectiveThroughMessageId: 30},
        {needsConfirmation: false}, {latestCustomerMessageId: Number.MAX_SAFE_INTEGER + 1}, {unexpected: true}])
        assert.equal(isConfirmationState(current(extra)), false);
});
test("GET and refresh never write, and a confirmation requires an explicit selection", async () => {
    let calls = 0; const h = harness(async () => { calls++; return response(current()); });
    await h.client.read(1); await h.client.confirm(); await h.client.read(1);
    assert.equal(calls, 2); assert.equal(h.states.at(-1).selectedTarget, null);
});
test("the selected target stays 30 when a newer customer message 31 arrives", async () => {
    const posts = []; let saved = false;
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") {
            posts.push(options); saved = true;
            return response(change({currentConfirmation: current({effectiveThroughMessageId: 30, latestCustomerMessageId: 31})}), 201);
        }
        return response(saved ? current({effectiveThroughMessageId: 30, latestCustomerMessageId: 31}) : current());
    });
    await h.client.read(1); h.client.choose(30); await h.client.confirm();
    assert.deepEqual(JSON.parse(posts[0].body), {clientRequestId: key, throughMessageId: 30, expectedAssignmentVersion: 1});
    assert.equal(posts[0].headers["X-CSRF-TOKEN"], "synthetic-token"); assert.equal(posts[0].credentials, "same-origin");
    assert.equal(h.states.at(-1).currentConfirmation.needsConfirmation, true);
    assert.equal(h.states.at(-1).currentConfirmation.latestCustomerMessageId, 31);
});
test("unknown POST keeps the original UUID, target and assignment across handoff and read refresh", async () => {
    let snapshot = current(); const posts = []; let keys = 0;
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") {
            posts.push(JSON.parse(options.body)); if (posts.length === 1) throw new Error("synthetic lost reply");
            return response(change({currentConfirmation: snapshot}), 200);
        }
        return response(snapshot);
    }, () => { keys++; return key; });
    await h.client.read(1); h.client.choose(30); await h.client.confirm();
    assert.equal(h.states.at(-1).kind, "unknown-outcome");
    snapshot = current({assignmentVersion: 2, latestCustomerMessageId: 31, canConfirm: false});
    await h.client.read(1); h.client.choose(31);
    assert.equal(h.states.at(-1).kind, "unconfirmed-change");
    await h.client.confirm(); assert.deepEqual(posts[1], posts[0]); assert.equal(keys, 1);
    assert.equal(h.states.at(-1).currentConfirmation.assignmentVersion, 2);
    assert.equal(h.states.at(-1).currentConfirmation.effectiveThroughMessageId, null);
    assert.equal(h.states.find((state) => state.kind === "saved").confirmation.assignmentVersion, 1);
});
test("A-B-A and refresh do not silently replace the assignment captured when selecting a target", async () => {
    let snapshot = current(); const posts = [];
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") { posts.push(JSON.parse(options.body)); return response({}, 409); }
        return response(snapshot);
    });
    await h.client.read(1); h.client.choose(30); snapshot = current({assignmentVersion: 3});
    await h.client.read(1); await h.client.confirm();
    assert.equal(posts[0].expectedAssignmentVersion, 1);
    assert.equal(h.states.at(-1).kind, "conflict");
});
test("saved confirmation followed by failed refresh retries GET without creating another submission", async () => {
    let posts = 0, gets = 0;
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") { posts++; return response(change(), 201); }
        return ++gets === 2 ? response({}, 503) : response(current({effectiveThroughMessageId: 30, needsConfirmation: false}));
    });
    await h.client.read(1); h.client.choose(30); await h.client.confirm();
    assert.equal(h.states.at(-1).kind, "saved-refresh-failed");
    await h.client.read(1); await h.client.confirm(); assert.equal(posts, 1);
    assert.equal(h.states.at(-1).kind, "invalid-input");
});
test("newest read wins even when an older response JSON arrives later", async () => {
    const old = deferred(); let reads = 0;
    const h = harness(async () => ++reads === 1 ? {ok: true, json: () => old.promise}
        : response(current({assignmentVersion: 2, canConfirm: false})));
    const first = h.client.read(1); await nextTick(); await h.client.read(1); old.resolve(current()); await first;
    assert.equal(h.states.at(-1).currentConfirmation.assignmentVersion, 2);
});
test("late POST for an old Ticket is ignored while its database outcome is independent", async () => {
    const held = deferred(), started = deferred();
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") { started.resolve(); return held.promise; }
        return response(current({assignmentVersion: url.includes("/2/") ? 2 : 1}));
    });
    await h.client.read(1); h.client.choose(30); const post = h.client.confirm(); await started.promise;
    await h.client.read(2); held.resolve(response(change(), 201)); await post;
    assert.equal(h.states.at(-1).currentConfirmation.assignmentVersion, 2);
    assert.equal(h.states.some((state) => state.kind === "saved"), false);
});
test("clear while obtaining CSRF prevents the old Ticket POST", async () => {
    const held = deferred(), started = deferred(); let posts = 0;
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") { started.resolve(); return held.promise; }
        if (options.method === "POST") posts++;
        return response(current());
    });
    await h.client.read(1); h.client.choose(30); const first = h.client.confirm(); await started.promise;
    h.client.clear(); held.resolve(csrf()); await first; assert.equal(posts, 0); assert.equal(h.states.at(-1).kind, "cleared");
});
test("suspended read cannot re-expose old staff state", async () => {
    const held = deferred(); const h = harness(() => held.promise);
    const read = h.client.read(1); h.client.suspend(); const count = h.states.length;
    held.resolve(response(current())); await read; assert.equal(h.states.length, count);
});
test("a non-assignee or invalid selection cannot generate a POST", async () => {
    let calls = 0;
    const h = harness(async () => { calls++; return response(current({canConfirm: false})); });
    await h.client.read(1); h.client.choose(30); await h.client.confirm(); assert.equal(calls, 1);
});
for (const status of [400, 401, 403, 409, 500, 503]) test(`HTTP ${status} is not a successful confirmation and retains the exact pending request`, async () => {
    const posts = [];
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") { posts.push(options.body); return response({}, status); }
        return response(current());
    });
    await h.client.read(1); h.client.choose(30); await h.client.confirm(); await h.client.confirm();
    assert.equal(posts.length, 2); assert.equal(posts[0], posts[1]);
    assert.equal(h.states.some((state) => state.kind === "saved"), false);
});
test("duplicate clicks send only one request while it is in flight", async () => {
    const held = deferred(), started = deferred(); let posts = 0;
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") { posts++; started.resolve(); return held.promise; }
        return response(current());
    });
    await h.client.read(1); h.client.choose(30); const first = h.client.confirm(); await started.promise;
    await h.client.confirm(); held.resolve(response(change(), 201)); await first; assert.equal(posts, 1);
});
test("mismatched saved target is unconfirmed and does not consume the original pending request", async () => {
    const posts = [];
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") { posts.push(options.body); return response(change({confirmation: {...change().confirmation, throughMessageId: 31}}), 201); }
        return response(current());
    });
    await h.client.read(1); h.client.choose(30); await h.client.confirm();
    assert.equal(h.states.at(-1).kind, "unknown-outcome");
    await h.client.confirm(); assert.equal(posts[1], posts[0]);
});

test("typed version rejection requires explicit reset, fresh read, new selection and a new UUID", async () => {
    let snapshot = current(), keys = 0; const posts = [];
    const nextKey = "22222222-2222-4222-8222-222222222222";
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") {
            posts.push(JSON.parse(options.body));
            if (posts.length === 1) { snapshot = current({assignmentVersion: 3}); return response({code: "ASSIGNMENT_VERSION_CONFLICT"}, 409); }
            snapshot = current({assignmentVersion: 3, effectiveThroughMessageId: 30, needsConfirmation: false});
            return response(change({confirmation: {...change().confirmation, assignmentVersion: 3}, currentConfirmation: snapshot}), 201);
        }
        return response(snapshot);
    }, () => ++keys === 1 ? key : nextKey);
    await h.client.read(1); h.client.choose(30); await h.client.confirm();
    assert.equal(h.states.at(-1).kind, "version-conflict");
    assert.equal(h.states.at(-1).canReselect, true); assert.equal(h.states.at(-1).canReconfirm, false);
    assert.equal(h.states.at(-1).selectedVersion, 1);
    await h.client.read(1); h.client.choose(31); await h.client.confirm();
    assert.equal(posts.length, 1); assert.equal(h.states.at(-1).kind, "selection-review-required");
    assert.equal(h.states.at(-1).selectedTarget, 30); assert.equal(h.states.at(-1).selectedVersion, 1);
    assert.equal(await h.client.reselect(), true);
    assert.equal(h.states.at(-1).selectedTarget, null); assert.equal(h.states.at(-1).canReselect, false);
    await h.client.confirm(); assert.equal(posts.length, 1); assert.equal(keys, 1);
    h.client.choose(30); await h.client.confirm();
    assert.deepEqual(posts, [{clientRequestId: key, throughMessageId: 30, expectedAssignmentVersion: 1},
        {clientRequestId: nextKey, throughMessageId: 30, expectedAssignmentVersion: 3}]);
});
for (const [name, status, body] of [
    ["payload conflict", 409, {code: "CONFIRMATION_REQUEST_CONFLICT"}], ["unknown code", 409, {code: "OTHER"}],
    ["missing code", 409, {}], ["null code", 409, {code: null}], ["array", 409, ["ASSIGNMENT_VERSION_CONFLICT"]],
    ["server failure", 500, {code: "ASSIGNMENT_VERSION_CONFLICT"}], ["not assignee", 403, {code: "ASSIGNMENT_VERSION_CONFLICT"}],
    ["missing permission", 404, {code: "ASSIGNMENT_VERSION_CONFLICT"}], ["unreadable body", 409, undefined]
]) test(`${name} cannot retire an unconfirmed command or offer version reselection`, async () => {
    let reads = 0; const posts = [];
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") { posts.push(options.body); return body === undefined ? new Response("invalid-json", {status}) : response(body, status); }
        reads++; return response(current());
    });
    await h.client.read(1); h.client.choose(30); await h.client.confirm();
    assert.equal(h.states.at(-1).canReselect, false); assert.equal(h.states.at(-1).canReconfirm, true);
    assert.equal(await h.client.reselect(), false); assert.equal(reads, 1);
    h.client.choose(31); await h.client.confirm();
    assert.equal(posts.length, 2); assert.equal(posts[0], posts[1]);
});
for (const status of [401, 404, 503]) test(`reselection GET ${status} does not grant a new write or re-use the old target`, async () => {
    let reads = 0, posts = 0;
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") { posts++; return response({code: "ASSIGNMENT_VERSION_CONFLICT"}, 409); }
        return ++reads === 1 ? response(current()) : response({}, status);
    });
    await h.client.read(1); h.client.choose(30); await h.client.confirm(); await h.client.reselect();
    assert.equal(h.states.at(-1).selectedTarget, null); assert.equal(h.states.at(-1).canReconfirm, false);
    h.client.choose(30); await h.client.confirm(); assert.equal(posts, 1);
});
test("a late reselection GET cannot replace another Ticket's scope", async () => {
    const held = deferred(), started = deferred(); let reads = 0;
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") return response({code: "ASSIGNMENT_VERSION_CONFLICT"}, 409);
        if (++reads === 2) { started.resolve(); return held.promise; }
        return response(current({assignmentVersion: reads === 1 ? 1 : 2}));
    });
    await h.client.read(1); h.client.choose(30); await h.client.confirm();
    const reset = h.client.reselect(); await started.promise; await h.client.read(2);
    held.resolve(response(current({assignmentVersion: 3}))); await reset;
    assert.equal(h.states.at(-1).currentConfirmation.assignmentVersion, 2); assert.equal(h.states.at(-1).canReselect, false);
    assert.equal(h.states.at(-1).selectedTarget, null);
});

test("staff panel offers loaded customer messages only and customer views make no staff-state request", async () => {
    const nodes = new Map(); let posts = 0, calls = 0, stored = false, rejectVersion = false, snapshotVersion = 1;
    const node = (selector) => {
        if (!nodes.has(selector)) nodes.set(selector, {hidden: false, value: "", textContent: "", disabled: false, options: [], listeners: new Map(),
            addEventListener(name, handler) { this.listeners.set(name, handler); }, add(option) { this.options.push(option); },
            replaceChildren(...options) { this.options = options; this.value = ""; }});
        return nodes.get(selector);
    };
    const oldOption = globalThis.Option;
    globalThis.Option = class { constructor(label, value) { this.textContent = label; this.value = value; } };
    try {
        const panel = attachCustomerConfirmations({document: {querySelector: node}, newRequestId: () => key, fetchImpl: async (url, options) => {
            calls++;
            if (url === "/api/csrf") return csrf();
            if (options.method === "POST") {
                posts++;
                if (rejectVersion) { snapshotVersion = 3; return response({code: "ASSIGNMENT_VERSION_CONFLICT"}, 409); }
                stored = true; const sent = JSON.parse(options.body); assert.equal(sent.throughMessageId, 1);
                return response(change({confirmation: {...change().confirmation, throughMessageId: 1},
                    currentConfirmation: current({latestCustomerMessageId: 3, effectiveThroughMessageId: 1})}), 201);
            }
            return response(current({assignmentVersion: snapshotVersion, latestCustomerMessageId: 3, effectiveThroughMessageId: stored && snapshotVersion === 1 ? 1 : null}));
        }});
        const conversation = (staff) => ({kind: "conversation", conversation: {ticket: {id: 1}, capabilities: {canViewConfirmations: staff},
            messages: [{id: 1, type: "INQUIRY"}, {id: 2, type: "OFFICIAL_REPLY"}, {id: 3, type: "CUSTOMER_FOLLOWUP"}]}});
        panel.observe(conversation(false)); assert.equal(calls, 0); assert.equal(node("#customer-confirmations").hidden, true);
        panel.observe(conversation(true)); await nextTick();
        assert.deepEqual(node("#confirmation-target").options.map((entry) => entry.value), ["", "1", "3"]);
        assert.equal(node("#confirmation-send").disabled, true); assert.equal(posts, 0);
        node("#confirmation-target").value = "1"; node("#confirmation-target").listeners.get("change")();
        node("#confirmation-form").listeners.get("submit")({preventDefault() {}}); await nextTick();
        assert.equal(posts, 1); assert.match(node("#confirmation-current").textContent, /확인 필요/u);
        assert.match(node("#confirmation-result").textContent, /#1/);
        rejectVersion = true; node("#confirmation-target").value = "3"; node("#confirmation-target").listeners.get("change")();
        node("#confirmation-form").listeners.get("submit")({preventDefault() {}}); await nextTick();
        assert.equal(node("#confirmation-reselect").hidden, false); assert.equal(node("#confirmation-send").disabled, true);
        node("#confirmation-refresh").listeners.get("click")(); await nextTick();
        assert.equal(node("#confirmation-target").disabled, true); assert.equal(node("#confirmation-send").disabled, true);
        node("#confirmation-reselect").listeners.get("click")(); await nextTick();
        assert.equal(node("#confirmation-target").value, ""); assert.equal(node("#confirmation-target").disabled, false);
        assert.equal(node("#confirmation-send").disabled, true); assert.equal(node("#confirmation-reselect").hidden, true);
        assert.equal(posts, 2);
        panel.clear(); assert.equal(node("#customer-confirmations").hidden, true); assert.equal(node("#confirmation-result").textContent, "");
    } finally { if (oldOption === undefined) delete globalThis.Option; else globalThis.Option = oldOption; }
});
