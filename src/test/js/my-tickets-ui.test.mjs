import test from "node:test";
import assert from "node:assert/strict";
import {createMyTicketsClient, isMyTicketsPage} from "../../main/resources/static/my-tickets-ui.mjs";

const item = (id, time = "2026-11-03T01:00:00.123456Z") => ({ticketId: id, title: "<script>synthetic()</script>", status: "OPEN", lastActivityAt: time});
const page = (items = [item(1)], next = null) => ({items, page: {hasMore: next !== null, nextCursor: next}});
const response = (body, status = 200) => new Response(JSON.stringify(body), {status});
function harness(fetchImpl) {
    const states = [], busy = [];
    return {states, busy, client: createMyTicketsClient({fetchImpl, view: {
        render: (state) => states.push(state), setLoading: (value) => busy.push(value)
    }})};
}
function deferred() {let resolve; const promise = new Promise((done) => {resolve = done;}); return {promise, resolve};}

test("list contract accepts nullable/microsecond activity without inventing client timestamp order", () => {
    assert.equal(isMyTicketsPage(page([item(1), item(3, "2026-11-03T01:00:00.123455Z"), item(2, null)])), true);
    assert.equal(isMyTicketsPage(page([])), true);
    for (const invalid of [page([item(1), item(1)]), page([item(Number.MAX_SAFE_INTEGER + 1)]),
        page([{...item(1), requesterId: 4}]), page([item(1, "invalid date")]),
        page([], "abc"), {...page(), other: 1}, page([item(1)], "x".repeat(1025)),
        {...page(), page: {hasMore: false, nextCursor: "abc"}}]) assert.equal(isMyTicketsPage(invalid), false);
});

test("own list sends only bounded page query and appends with Ticket ID deduplication", async () => {
    const requests = [], replies = [page([item(3), item(2)], "Y3Vyc29y"), page([item(2), item(1, null)])];
    const h = harness(async (url, options) => {requests.push({url, options}); return response(replies.shift());});
    await h.client.refresh(); await h.client.more(); await h.client.more();
    assert.deepEqual(h.states.at(-1).items.map((v) => v.ticketId), [3, 2, 1]);
    assert.equal(requests.length, 2);
    assert.equal(requests[1].url, "/api/my/tickets?limit=50&cursor=Y3Vyc29y");
    assert.equal(requests[0].options.credentials, "same-origin");
    assert.equal(requests[0].options.cache, "no-store");
    assert.equal(requests[0].options.method, "GET");
});

test("refresh ignores an older response even when abort is ignored", async () => {
    const old = deferred(); let calls = 0;
    const h = harness(() => ++calls === 1 ? old.promise : Promise.resolve(response(page([item(2)]))));
    const first = h.client.refresh(); await h.client.refresh(); old.resolve(response(page([item(1)]))); await first;
    assert.deepEqual(h.states.at(-1).items.map((v) => v.ticketId), [2]);
});

test("clear invalidates in-flight list and pagination state", async () => {
    const wait = deferred(); const h = harness(() => wait.promise);
    const loading = h.client.refresh(); h.client.clear(); wait.resolve(response(page())); await loading;
    assert.equal(h.states.at(-1).kind, "cleared"); await h.client.more();
    assert.equal(h.states.at(-1).kind, "cleared");
});

test("failed next page retries the same boundary while authentication loss clears it", async () => {
    const urls = [], replies = [response(page([item(2)], "YQ")), response({}, 503), response(page([item(1)]))];
    const h = harness(async (url) => {urls.push(url); return replies.shift();});
    await h.client.refresh(); await h.client.more(); await h.client.more();
    assert.equal(urls[1], urls[2]); assert.equal(h.states.at(-1).items.length, 2);
    const denied = harness(async () => response({}, 401));
    await denied.client.refresh(); await denied.client.more(); assert.equal(denied.states.at(-1).kind, "login-required");
});

test("invalid response or unchanged next cursor cannot silently loop", async () => {
    const replies = [page([item(2)], "YQ"), page([item(1)], "YQ")];
    const h = harness(async () => response(replies.shift()));
    await h.client.refresh(); await h.client.more(); assert.equal(h.states.at(-1).kind, "invalid-response");
});

test("parallel next-page clicks issue one request", async () => {
    const wait = deferred(); let calls = 0;
    const h = harness(async () => ++calls === 1 ? response(page([item(2)], "YQ")) : wait.promise);
    await h.client.refresh(); const next = h.client.more(); await h.client.more();
    assert.equal(calls, 2); wait.resolve(response(page([item(1)]))); await next;
});
