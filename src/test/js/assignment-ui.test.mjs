import test from "node:test";
import assert from "node:assert/strict";
import {createAssignmentClient, isAssignment, isSupportPage} from "../../main/resources/static/assignment-ui.mjs";

const key = "11111111-1111-4111-8111-111111111111";
const assigned = (ticketId = 1, owner = null, version = 0) => ({ticketId, serviceId: 1, assigneeAgentId: owner, version,
    canClaim: owner === null, canTransfer: owner === 10, canReply: owner === 10});
const response = (body, status = 200) => new Response(JSON.stringify(body), {status});
const csrf = () => response({headerName: "X-CSRF-TOKEN", token: "synthetic-token"});
const result = (data = assigned(1, 10, 1), type = "CLAIM", extra = {}) => ({assignment: data,
    event: {id: 1, ticketId: 1, type, toAgentId: 10, assignmentVersion: 1, ...extra}});
function harness(fetchImpl) {
    const states = [], busy = [];
    return {states, busy, client: createAssignmentClient({fetchImpl, newRequestId: () => key,
        view: {render: (state) => states.push(state), setBusy: (value) => busy.push(value)}})};
}
function deferred() { let resolve; const promise = new Promise((done) => { resolve = done; }); return {resolve, promise}; }

test("assignment state and ordered bounded pages reject unsafe or inconsistent values", () => {
    assert.equal(isAssignment(assigned(), 1), true);
    assert.equal(isAssignment({...assigned(), version: 1}, 1), false);
    assert.equal(isAssignment(assigned(1, 10, 1), 2), false);
    assert.equal(isAssignment({...assigned(), serviceId: Number.MAX_SAFE_INTEGER + 1}, 1), false);
    assert.equal(isSupportPage({items: [{id: 2}, {id: 3}], hasMore: true, nextAfterId: 3}), true);
    assert.equal(isSupportPage({items: [{id: 2}, {id: 1}], hasMore: false, nextAfterId: null}), false);
    assert.equal(isSupportPage({items: [], hasMore: true, nextAfterId: 1}), false);
});
test("invalid selection sends no request and cannot claim", async () => {
    let count = 0; const h = harness(async () => { count++; return response({}); });
    await h.client.read(0); await h.client.change("CLAIM"); assert.equal(count, 0);
});
test("claim requires CSRF and the server-supplied version, then refreshes only by GET", async () => {
    const calls = [];
    const h = harness(async (url, options) => {
        calls.push({url, options});
        if (url === "/api/csrf") return csrf();
        return options.method === "POST" ? response(result(), 201) : response(assigned());
    });
    await h.client.read(1); await h.client.change("CLAIM");
    const posted = calls.filter((call) => call.options.method === "POST");
    assert.equal(posted.length, 1);
    assert.deepEqual(JSON.parse(posted[0].options.body), {clientRequestId: key, expectedAssignmentVersion: 0});
    assert.equal(posted[0].options.headers["X-CSRF-TOKEN"], "synthetic-token");
    assert.equal(posted[0].options.credentials, "same-origin");
    assert.equal(calls.at(-1).url, "/api/tickets/1/assignment");
});
test("unknown transfer retains exact UUID, reason, target and version despite a newer read", async () => {
    const posts = []; let latest = assigned(1, 10, 1);
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") { posts.push(JSON.parse(options.body)); throw new Error("synthetic network loss"); }
        return response(latest);
    });
    await h.client.read(1); await h.client.change("TRANSFER", 20, " original ");
    latest = assigned(1, 20, 2); await h.client.read(1);
    await h.client.change("TRANSFER", 30, "edited"); assert.equal(posts.length, 1);
    await h.client.change("TRANSFER", 20, " original "); assert.equal(posts.length, 2);
    assert.deepEqual(posts[0], posts[1]); assert.equal(posts[1].expectedAssignmentVersion, 1);
});
test("replay renders current assignment, never the assignee of the old event", async () => {
    const h = harness(async (url, options) => url === "/api/csrf" ? csrf()
        : options.method === "POST" ? response(result(assigned(1, 20, 2)), 200) : response(assigned()));
    await h.client.read(1); await h.client.change("CLAIM");
    const saved = h.states.find((state) => state.kind === "change-saved");
    assert.equal(saved.event.toAgentId, 10); assert.equal(saved.assignment.assigneeAgentId, 20);
});
test("successful change followed by failed read never resends POST", async () => {
    let reads = 0, posts = 0;
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") { posts++; return response(result(), 201); }
        return ++reads === 1 ? response(assigned()) : response({}, 503);
    });
    await h.client.read(1); await h.client.change("CLAIM");
    assert.equal(posts, 1); assert.equal(h.states.at(-1).kind, "saved-refresh-failed");
});
test("late GET and POST after ticket change are ignored", async () => {
    const pending = deferred(); let posts = false;
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") { posts = true; return pending.promise; }
        return response(assigned(url.includes("/2/") ? 2 : 1));
    });
    await h.client.read(1); const first = h.client.change("CLAIM");
    while (!posts) await Promise.resolve();
    await h.client.read(2); pending.resolve(response(result(), 201)); await first;
    assert.equal(h.states.at(-1).assignment.ticketId, 2);
    assert.equal(h.states.some((state) => state.kind === "change-saved"), false);
});
test("latest GET wins even when transport ignores cancellation", async () => {
    const pending = deferred(); let calls = 0;
    const h = harness(() => ++calls === 1 ? pending.promise : Promise.resolve(response(assigned(1, 10, 1))));
    const first = h.client.read(1); await h.client.read(1); pending.resolve(response(assigned())); await first;
    assert.equal(h.states.at(-1).assignment.version, 1);
});
test("clear invalidates pending reads and capabilities", async () => {
    const pending = deferred(); const h = harness(() => pending.promise);
    const first = h.client.read(1); h.client.clear(); pending.resolve(response(assigned())); await first;
    assert.equal(h.states.at(-1).kind, "cleared"); await h.client.change("CLAIM"); assert.equal(h.states.at(-1).kind, "invalid-input");
});
for (const status of [400, 403, 409, 500]) test(`HTTP ${status} never assumes a saved assignment`, async () => {
    const h = harness(async (url, options) => url === "/api/csrf" ? csrf()
        : options.method === "POST" ? response({}, status) : response(assigned()));
    await h.client.read(1); await h.client.change("CLAIM");
    assert.equal(h.states.some((state) => state.kind === "change-saved"), false);
});
