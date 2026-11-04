import test from "node:test";
import assert from "node:assert/strict";
import {setImmediate as tick} from "node:timers/promises";
import {createTicketStatusClient, isTicketStatusCurrent} from "../../main/resources/static/ticket-status-ui.mjs";

const key = "11111111-1111-4111-8111-111111111111";
const otherKey = "22222222-2222-4222-8222-222222222222";
const current = (extra = {}) => ({status: "IN_PROGRESS", statusVersion: 1, assignmentVersion: 1, latestCustomerMessageId: 30,
    effectiveThroughMessageId: 30, canStart: false, canResolve: true, canReopen: false, ...extra});
const owner = (extra = {}) => ({status: "RESOLVED", statusVersion: 2, canReopen: true, ...extra});
const response = (body, status = 200) => new Response(JSON.stringify(body), {status});
const csrf = () => response({headerName: "X-CSRF-TOKEN", token: "synthetic-token"});
const edit = (extra = {}) => ({action: "RESOLVE", reason: "Resolved", relatedReplyMessageId: 20, throughCustomerMessageId: 30, ...extra});
const result = (command, snapshot = current({status: "RESOLVED", statusVersion: 2, canResolve: false, canReopen: true}), scope = "STAFF") => {
    const action = scope === "STAFF" ? command.action : "REOPEN";
    const states = {START: ["OPEN", "IN_PROGRESS"], RESOLVE: ["IN_PROGRESS", "RESOLVED"], REOPEN: ["RESOLVED", "OPEN"]};
    const saved = {id: 1, fromStatus: states[action][0], toStatus: states[action][1], statusVersion: command.expectedStatusVersion + 1,
        reason: command.reason, recordedAt: "2026-11-04T01:00:00Z"};
    if (scope === "STAFF") Object.assign(saved, {ticketId: 1, actorUserId: 10, clientRequestId: command.clientRequestId, requestScope: "STAFF",
        action, expectedStatusVersion: command.expectedStatusVersion, expectedAssignmentVersion: command.expectedAssignmentVersion,
        assignmentVersion: command.expectedAssignmentVersion, relatedReplyMessageId: command.relatedReplyMessageId ?? null,
        throughCustomerMessageId: command.throughCustomerMessageId ?? null});
    return {change: saved, current: snapshot};
};
function harness(fetchImpl, newRequestId = () => key) {
    const states = [];
    return {states, client: createTicketStatusClient({fetchImpl, newRequestId, view: {render: (state) => states.push(state)}})};
}
function deferred() {let resolve;const promise = new Promise((done) => {resolve = done;});return {promise, resolve};}

test("status contracts keep staff metadata out of the owner projection", () => {
    assert.equal(isTicketStatusCurrent(current(), "STAFF"), true);
    assert.equal(isTicketStatusCurrent(owner(), "REQUESTER"), true);
    assert.equal(isTicketStatusCurrent(owner({assignmentVersion: 1}), "REQUESTER"), false);
    for (const extra of [{statusVersion: null}, {statusVersion: -1}, {statusVersion: Number.MAX_SAFE_INTEGER + 1},
        {canStart: true}, {effectiveThroughMessageId: null}, {latestCustomerMessageId: 0}, {extra: true}])
        assert.equal(isTicketStatusCurrent(current(extra), "STAFF"), false);
});
test("GET and refresh do not create IDs or status writes", async () => {
    const calls = [];let keys = 0;
    const h = harness(async (url, options) => {calls.push({url, options});return response(current());}, () => {keys++;return key;});
    await h.client.open(1, "STAFF");await h.client.refresh();await h.client.submit();
    assert.equal(calls.length, 2);assert.equal(keys, 0);
    assert.equal(calls[0].options.credentials, "same-origin");assert.equal(calls[0].options.cache, "no-store");
});
test("resolution sends explicit selected references and frozen versions with Session and CSRF", async () => {
    const posts = [];let committed = false;
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") {posts.push(options);committed = true;return response(result(JSON.parse(options.body)), 201);}
        return response(committed ? current({status: "RESOLVED", statusVersion: 2, canResolve: false, canReopen: true}) : current());
    });
    await h.client.open(1, "STAFF");h.client.edit(edit());await h.client.submit();
    assert.deepEqual(JSON.parse(posts[0].body), {clientRequestId: key, action: "RESOLVE", expectedStatusVersion: 1,
        expectedAssignmentVersion: 1, reason: "Resolved", relatedReplyMessageId: 20, throughCustomerMessageId: 30});
    assert.equal(posts[0].credentials, "same-origin");assert.equal(posts[0].headers["X-CSRF-TOKEN"], "synthetic-token");
    assert.equal(h.states.at(-1).saved.statusVersion, 2);assert.equal(h.states.at(-1).canReconfirm, false);
});
test("owner reopen uses only the public command and no staff fields", async () => {
    const posts = [];let saved = false;
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") {posts.push({url, body: JSON.parse(options.body)});saved = true;
            return response(result(posts[0].body, owner({status: "OPEN", statusVersion: 3, canReopen: false}), "REQUESTER"), 201);}
        return response(saved ? owner({status: "OPEN", statusVersion: 3, canReopen: false}) : owner());
    });
    await h.client.open(1, "REQUESTER");h.client.edit(edit({reason: "Again"}));await h.client.submit();
    assert.equal(posts[0].url, "/api/tickets/1/customer-status/reopen");
    assert.deepEqual(posts[0].body, {clientRequestId: key, expectedStatusVersion: 2, reason: "Again"});
    assert.equal(h.states.at(-1).current.status, "OPEN");
});
test("a same-ticket read never promotes the selected customer range", async () => {
    let snapshot = current();const posts = [];
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") {posts.push(JSON.parse(options.body));return response({code: "NEW_CUSTOMER_MESSAGE"}, 409);}
        return response(snapshot);
    });
    await h.client.open(1, "STAFF");h.client.edit(edit());snapshot = current({latestCustomerMessageId: 31, effectiveThroughMessageId: 31});
    await h.client.refresh();await h.client.submit();
    assert.equal(posts[0].throughCustomerMessageId, 30);assert.equal(h.states.at(-1).canReset, true);
});
test("typed rejection needs explicit reset and a new decision, and reset itself sends only GET", async () => {
    let snapshot = current();const posts = [];let keys = 0;
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") {posts.push(JSON.parse(options.body));return response({code: "STATUS_VERSION_CONFLICT"}, 409);}
        return response(snapshot);
    }, () => ++keys === 1 ? key : otherKey);
    await h.client.open(1, "STAFF");h.client.edit(edit());await h.client.submit();
    snapshot = current({statusVersion: 5});await h.client.refresh();
    assert.equal(h.states.at(-1).canReset, true);assert.equal(h.states.at(-1).draft.expectedStatusVersion, 1);
    await h.client.resetRejected();assert.equal(posts.length, 1);assert.equal(keys, 1);assert.equal(h.states.at(-1).draft, null);
    h.client.edit(edit());await h.client.submit();assert.equal(posts[1].expectedStatusVersion, 5);assert.equal(posts[1].clientRequestId, otherKey);
});
test("unknown saved resolution replays original UUID and fields after reopen and reassignment", async () => {
    let snapshot = current();const posts = [];let keys = 0;
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") {posts.push(JSON.parse(options.body));if (posts.length === 1) throw new Error("lost response");
            return response(result(posts[0], snapshot), 200);}
        return response(snapshot);
    }, () => {keys++;return key;});
    await h.client.open(1, "STAFF");h.client.edit(edit());await h.client.submit();
    snapshot = current({status: "OPEN", statusVersion: 3, assignmentVersion: 3, canStart: true, canResolve: false});
    await h.client.refresh();assert.equal(h.client.edit(edit({reason: "changed"})), false);
    assert.equal(await h.client.resetRejected(), false);await h.client.submit();
    assert.deepEqual(posts[0], posts[1]);assert.equal(keys, 1);
    assert.equal(h.states.at(-1).saved.toStatus, "RESOLVED");assert.equal(h.states.at(-1).current.status, "OPEN");
});
for (const [http, code] of [[409, "STATUS_REQUEST_CONFLICT"], [500, "STATUS_VERSION_CONFLICT"], [403, "OTHER"], [404, "STATUS_NOT_FOUND"]]) {
    test(`unconfirmed error ${http}/${code} keeps the original command`, async () => {
        const posts = [];
        const h = harness(async (url, options) => {
            if (url === "/api/csrf") return csrf();
            if (options.method === "POST") {posts.push(JSON.parse(options.body));return response({code}, http);}
            return response(current());
        });
        await h.client.open(1, "STAFF");h.client.edit(edit());await h.client.submit();await h.client.submit();
        assert.deepEqual(posts[0], posts[1]);assert.equal(h.states.at(-1).canReset, false);assert.equal(h.states.at(-1).canReconfirm, true);
    });
}
test("unreadable error and malformed success never discard an unconfirmed command", async () => {
    for (const mode of ["error", "invalid-success", "wrong-content"]) {
        const h = harness(async (url, options) => {
            if (url === "/api/csrf") return csrf();
            if (options.method === "POST") {
                if (mode === "error") return new Response("not json", {status: 409});
                if (mode === "invalid-success") return response({}, 201);
                const data = result(JSON.parse(options.body));data.change.reason = "Different";return response(data, 201);
            }
            return response(current());
        });
        await h.client.open(1, "STAFF");h.client.edit(edit());await h.client.submit();
        assert.equal(h.states.at(-1).canReconfirm, true);assert.equal(h.states.at(-1).canReset, false);
    }
});
test("saved success followed by GET failure retries only GET and retains its confirmed history", async () => {
    let posts = 0, gets = 0, fail = true;
    const h = harness(async (url, options) => {
        if (url === "/api/csrf") return csrf();
        if (options.method === "POST") {posts++;return response(result(JSON.parse(options.body)), 201);}
        gets++;return gets > 1 && fail ? response({}, 503) : response(current());
    });
    await h.client.open(1, "STAFF");h.client.edit(edit());await h.client.submit();
    assert.equal(h.states.at(-1).kind, "saved-refresh-failed");assert.equal(h.states.at(-1).saved.statusVersion, 2);
    fail = false;await h.client.refresh();await h.client.submit();assert.equal(posts, 1);assert.equal(gets, 3);
});
test("an unsent draft keeps status and assignment versions until explicit reset", async () => {
    let snapshot = current();let keys = 0, posts = 0;
    const h = harness(async (_url, options) => {if (options.method === "POST") posts++;return response(snapshot);}, () => {keys++;return key;});
    await h.client.open(1, "STAFF");h.client.edit(edit());snapshot = current({statusVersion: 6, assignmentVersion: 3});await h.client.refresh();
    assert.equal(h.states.at(-1).draft.expectedStatusVersion, 1);assert.equal(h.states.at(-1).canReset, true);
    assert.equal(h.states.at(-1).canSubmit, false);await h.client.resetRejected();assert.equal(posts, 0);assert.equal(keys, 0);
});
test("late reads for the same ticket and for a previous selection cannot replace current state", async () => {
    const first = deferred(), second = deferred();let count = 0;
    const h = harness(async () => ++count === 1 ? first.promise : count === 2 ? second.promise : response(current({statusVersion: 9})));
    const a = h.client.open(1, "STAFF"), b = h.client.refresh();second.resolve(response(current({statusVersion: 5})));await b;
    first.resolve(response(current()));await a;assert.equal(h.states.at(-1).current.statusVersion, 5);
    await h.client.open(2, "STAFF");assert.equal(h.states.at(-1).current.statusVersion, 9);
});
test("changing ticket during CSRF fetch prevents the original POST", async () => {
    const gate = deferred();let posts = 0, keys = 0;
    const h = harness(async (url, options) => {if (url === "/api/csrf") return gate.promise;if (options.method === "POST") posts++;return response(current());}, () => {keys++;return key;});
    await h.client.open(1, "STAFF");h.client.edit(edit());const writing = h.client.submit();await tick();
    await h.client.open(2, "STAFF");gate.resolve(csrf());await writing;assert.equal(posts, 0);assert.equal(keys, 0);
});
test("a committed old selection response is ignored after moving to another ticket", async () => {
    const gate = deferred();let command;
    const h = harness(async (url, options) => {if (url === "/api/csrf") return csrf();if (options.method === "POST") {command = JSON.parse(options.body);return gate.promise;}return response(current());});
    await h.client.open(1, "STAFF");h.client.edit(edit());const writing = h.client.submit();await tick();
    await h.client.open(2, "STAFF");gate.resolve(response(result(command), 201));await writing;
    assert.equal(h.states.at(-1).saved, null);assert.equal(h.states.at(-1).current.statusVersion, 1);
});
test("invalid local reason/selection and clear never send mutations", async () => {
    let posts = 0;
    const h = harness(async (_url, options) => {if (options.method === "POST") posts++;return response(current());});
    await h.client.open(1, "STAFF");
    for (const extra of [{reason: " "}, {reason: "x".repeat(501)}, {relatedReplyMessageId: null}, {throughCustomerMessageId: 0}]) {
        h.client.edit(edit(extra));await h.client.submit();
    }
    h.client.suspend();assert.equal(h.states.at(-1).kind, "hidden");h.client.clear();assert.equal(h.states.at(-1).draft, null);assert.equal(posts, 0);
});
