import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import test from "node:test";

import {
    createTicketClient,
    isTicket
} from "../../main/resources/static/ticket-ui.mjs";

function jsonResponse(status, body) {
    return new Response(JSON.stringify(body), {
        status,
        headers: { "Content-Type": "application/json" }
    });
}

function recordView() {
    const states = [];
    const tickets = [];

    return {
        states,
        tickets,
        render: (state) => states.push(state),
        addTicket: (ticket) => tickets.push(ticket)
    };
}

function deferred() {
    let resolve;
    let reject;
    const promise = new Promise((resolvePromise, rejectPromise) => {
        resolve = resolvePromise;
        reject = rejectPromise;
    });

    return { promise, resolve, reject };
}

test("Ticket 검증은 JSON 문법과 별도로 필드·값을 확인한다", () => {
    assert.equal(isTicket({ id: 1, title: "로그인 오류", status: "OPEN" }), true);
    assert.equal(isTicket({ id: 1, title: " ", status: "OPEN" }), false);
    assert.equal(isTicket({ id: "1", title: "로그인 오류", status: "OPEN" }), false);
    assert.equal(isTicket({ id: 1, title: "로그인 오류", status: "UNKNOWN" }), false);
    assert.equal(isTicket(null), false);
});

test("정상 조회는 Ticket 구조 확인 뒤에만 성공 화면을 표시한다", async () => {
    const ticket = { id: 7, title: "로그인 오류", status: "OPEN" };
    const view = recordView();
    const client = createTicketClient({
        fetchImpl: async () => jsonResponse(200, ticket),
        view
    });

    await client.readTicket(7);

    assert.deepEqual(view.states.map((state) => state.kind), ["loading", "success"]);
    assert.deepEqual(view.states.at(-1).ticket, ticket);
});

test("HTTP 오류는 JSON 해석 전에 UI 상태로 구분한다", async () => {
    for (const [status, expected] of [
        [401, "login-required"],
        [403, "forbidden"],
        [404, "not-found"],
        [500, "http-error"]
    ]) {
        const view = recordView();
        const client = createTicketClient({
            fetchImpl: async () => new Response(null, { status }),
            view
        });

        await client.readTicket(1);

        assert.equal(view.states.at(-1).kind, expected);
        assert.equal(view.states.some((state) => state.kind === "success"), false);
    }
});

test("200이어도 JSON 문법이 깨지면 Ticket 검증에 도달하지 않는다", async () => {
    const view = recordView();
    const client = createTicketClient({
        fetchImpl: async () => new Response("<html>", { status: 200 }),
        view
    });

    await client.readTicket(1);

    assert.deepEqual(view.states.map((state) => state.kind),
        ["loading", "invalid-response"]);
});

test("JSON 해석에 성공해도 잘못된 Ticket은 표시하지 않는다", async () => {
    const view = recordView();
    const client = createTicketClient({
        fetchImpl: async () => jsonResponse(200, {
            id: 1,
            title: "",
            status: "OPEN"
        }),
        view
    });

    await client.readTicket(1);

    assert.deepEqual(view.states.map((state) => state.kind),
        ["loading", "invalid-response"]);
    assert.equal(view.states.some((state) => state.kind === "success"), false);
});

test("읽을 Response가 없으면 HTTP Status를 추정하지 않는다", async () => {
    const view = recordView();
    const client = createTicketClient({
        fetchImpl: async () => { throw new TypeError("request failed"); },
        view
    });

    await client.readTicket(1);

    assert.deepEqual(view.states.map((state) => state.kind),
        ["loading", "request-unavailable"]);
});

test("의도적으로 취소된 조회는 오류 화면을 표시하지 않는다", async () => {
    const view = recordView();
    const client = createTicketClient({
        fetchImpl: async () => {
            const error = new Error("cancelled");
            error.name = "AbortError";
            throw error;
        },
        view
    });

    await client.readTicket(1);

    assert.deepEqual(view.states.map((state) => state.kind), ["loading"]);
});

test("오래된 조회 실패는 최신 조회의 오류 화면을 다시 그리지 않는다", async () => {
    const first = deferred();
    const second = deferred();
    const view = recordView();
    const client = createTicketClient({
        fetchImpl: (url) => url.endsWith("/1") ? first.promise : second.promise,
        view
    });

    const firstJob = client.readTicket(1);
    const secondJob = client.readTicket(2);
    second.reject(new TypeError("request failed"));
    await secondJob;
    const stateCountAfterSecond = view.states.length;

    first.reject(new TypeError("late request failed"));
    await firstJob;

    assert.equal(view.states.at(-1).kind, "request-unavailable");
    assert.equal(view.states.length, stateCountAfterSecond);
});

test("오래된 정상 응답도 최신 조회의 실패 화면을 덮지 않는다", async () => {
    const first = deferred();
    const second = deferred();
    const view = recordView();
    const client = createTicketClient({
        fetchImpl: (url) => url.endsWith("/1") ? first.promise : second.promise,
        view
    });

    const firstJob = client.readTicket(1);
    const secondJob = client.readTicket(2);
    second.resolve(jsonResponse(200, { id: 2, title: "", status: "OPEN" }));
    await secondJob;
    const stateCountAfterSecond = view.states.length;

    first.resolve(jsonResponse(200, {
        id: 1,
        title: "이전 Ticket",
        status: "OPEN"
    }));
    await firstJob;

    assert.equal(view.states.at(-1).kind, "invalid-response");
    assert.equal(view.states.length, stateCountAfterSecond);
});

test("생성은 같은 Origin Session과 CSRF Header를 함께 사용한다", async () => {
    const ticket = { id: 8, title: "로그인 오류", status: "OPEN" };
    const csrfValue = randomUUID();
    const calls = [];
    const view = recordView();
    const client = createTicketClient({
        fetchImpl: async (url, options) => {
            calls.push({ url, options });
            if (url === "/api/csrf") {
                return jsonResponse(200, {
                    headerName: "X-CSRF-TOKEN",
                    token: csrfValue
                });
            }
            return jsonResponse(201, ticket);
        },
        view
    });

    await client.createTicket("로그인 오류");

    assert.deepEqual(calls.map((call) => call.url), ["/api/csrf", "/api/tickets"]);
    assert.equal(calls[0].options.credentials, "same-origin");
    assert.equal(calls[1].options.credentials, "same-origin");
    assert.equal(calls[1].options.method, "POST");
    assert.ok(calls[1].options.headers["X-CSRF-TOKEN"] === csrfValue);
    assert.deepEqual(JSON.parse(calls[1].options.body), { title: "로그인 오류" });
    assert.deepEqual(view.tickets, [ticket]);
    assert.equal(view.states.at(-1).kind, "created");
});

test("CSRF 응답 구조가 잘못되면 POST를 보내지 않는다", async () => {
    const calls = [];
    const view = recordView();
    const client = createTicketClient({
        fetchImpl: async (url) => {
            calls.push(url);
            return jsonResponse(200, { headerName: "X-CSRF-TOKEN" });
        },
        view
    });

    await client.createTicket("로그인 오류");

    assert.deepEqual(calls, ["/api/csrf"]);
    assert.equal(view.states.at(-1).kind, "invalid-response");
});

test("POST 응답을 읽지 못하면 성공·실패를 단정하거나 자동 재시도하지 않는다", async () => {
    const calls = [];
    const view = recordView();
    const client = createTicketClient({
        fetchImpl: async (url) => {
            calls.push(url);
            if (url === "/api/csrf") {
                return jsonResponse(200, {
                    headerName: "X-CSRF-TOKEN",
                    token: randomUUID()
                });
            }
            throw new TypeError("response unavailable");
        },
        view
    });

    await client.createTicket("로그인 오류");

    assert.deepEqual(calls, ["/api/csrf", "/api/tickets"]);
    assert.equal(view.states.at(-1).kind, "unknown-outcome");
    assert.deepEqual(view.tickets, []);
});
