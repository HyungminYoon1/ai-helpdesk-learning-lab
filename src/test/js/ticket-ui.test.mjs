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
    assert.equal(view.states.at(-1).beforeSubmission, true);
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

test("결과를 잃은 접수는 버튼을 눌렀을 때만 원래 UUID, 원문과 CSRF로 재확인한다", async () => {
    const original = "  합성 원문\n공백 보존  ";
    const requestId = randomUUID();
    const receiptContext = randomUUID();
    const originalToken = randomUUID();
    const calls = [];
    const view = recordView();
    const ticket = { id: 21, title: "문의", status: "OPEN" };
    let csrfReads = 0;
    let posts = 0;
    const client = createTicketClient({
        fetchImpl: async (url, options) => {
            calls.push({ url, options });
            if (url === "/api/csrf") {
                return jsonResponse(200, {
                    headerName: "X-CSRF-TOKEN",
                    token: csrfReads++ === 0 ? originalToken : randomUUID(),
                    receiptContext
                });
            }
            if (++posts === 1) {
                throw new TypeError("response lost");
            }
            return jsonResponse(200, ticket);
        },
        view,
        newRequestId: () => requestId
    });

    await client.createTicket("문의", original);
    assert.deepEqual(view.states.at(-1), { kind: "unknown-outcome", canConfirm: true });
    await client.createTicket("다른 제목", "다른 본문");
    assert.equal(posts, 1);

    await client.confirmReceipt();
    assert.equal(posts, 2);
    assert.equal(calls[1].options.body, calls[3].options.body);
    assert.deepEqual(JSON.parse(calls[3].options.body),
        { title: "문의", body: original, clientRequestId: requestId });
    assert.equal(calls[3].options.headers["X-CSRF-TOKEN"], originalToken);
    assert.deepEqual(view.states.at(-1), { kind: "receipt-confirmed", ticket });
    assert.deepEqual(view.tickets, [ticket]);
});

test("새 세션에서 이전 문의의 키나 원문을 재전송하지 않고 내 문의로 안내한다", async () => {
    const view = recordView();
    let posts = 0;
    const client = createTicketClient({
        fetchImpl: async (url) => {
            if (url === "/api/csrf") {
                return jsonResponse(200, {
                    headerName: "X-CSRF-TOKEN", token: randomUUID(), receiptContext: randomUUID()
                });
            }
            posts++;
            throw new TypeError("response lost");
        },
        view
    });

    await client.createTicket("문의", "첫 세션의 합성 원문");
    await client.confirmReceipt();
    assert.equal(posts, 1);
    assert.deepEqual(view.states.at(-1), { kind: "receipt-check-list" });
    await client.confirmReceipt();
    assert.equal(posts, 1);
});

test("POST 뒤 서버 오류나 응답 형식 오류도 같은 제출로 재확인할 수 있다", async () => {
    for (const initialResponse of [jsonResponse(500, {}), jsonResponse(201, { id: 1 })]) {
        const receiptContext = randomUUID();
        const view = recordView();
        const ticket = { id: 21, title: "문의", status: "OPEN" };
        const bodies = [];
        const client = createTicketClient({
            fetchImpl: async (url, options) => {
                if (url === "/api/csrf") {
                    return jsonResponse(200, { headerName: "X-CSRF-TOKEN", token: randomUUID(), receiptContext });
                }
                bodies.push(options.body);
                return bodies.length === 1 ? initialResponse : jsonResponse(201, ticket);
            },
            view
        });

        await client.createTicket("문의", "합성 문의 본문");
        assert.deepEqual(view.states.at(-1), { kind: "unknown-outcome", canConfirm: true });
        await client.confirmReceipt();
        assert.equal(bodies.length, 2);
        assert.equal(bodies[1], bodies[0]);
        assert.deepEqual(view.states.at(-1), { kind: "created", ticket });
    }
});

test("접수는 원문 body를 보존하며 작성자나 Role을 요청에 추가하지 않는다", async () => {
    const original = "  로그인 오류가 납니다.\n새 링크를 요청합니다.  ";
    const calls = [];
    const view = recordView();
    const client = createTicketClient({
        fetchImpl: async (url, options) => {
            calls.push({ url, options });
            return url === "/api/csrf"
                ? jsonResponse(200, { headerName: "X-CSRF-TOKEN", token: randomUUID() })
                : jsonResponse(201, { id: 9, title: "문의", status: "OPEN" });
        },
        view
    });
    await client.createTicket("문의", original);
    assert.deepEqual(JSON.parse(calls[1].options.body), { title: "문의", body: original });
    assert.equal(view.states.at(-1).kind, "created");
});

test("공백·null 본문은 CSRF 조회나 접수 요청 전에 거부한다", async () => {
    for (const body of [null, "", "   ", "\t\n", "\u2003"]) {
        let calls = 0;
        const view = recordView();
        const client = createTicketClient({
            fetchImpl: async () => { calls++; throw new Error("must not be called"); },
            view
        });
        await client.createTicket("문의", body);
        assert.equal(calls, 0);
        assert.equal(view.states.at(-1).kind, "invalid-body");
    }
});

test("2,001 Code Point 본문은 잘라 보내지 않고 거부한다", async () => {
    let calls = 0;
    const view = recordView();
    const client = createTicketClient({
        fetchImpl: async () => { calls++; throw new Error("must not be called"); },
        view
    });
    await client.createTicket("문의", "😀".repeat(2001));
    assert.equal(calls, 0);
    assert.equal(view.states.at(-1).kind, "invalid-body");
});

test("2,000 Emoji와 앞뒤 공백은 UTF-16 길이로 거부하거나 원문에서 제거하지 않는다", async () => {
    const original = "\u2003" + "😀".repeat(2000) + "\n";
    let submittedBody;
    const view = recordView();
    const client = createTicketClient({
        fetchImpl: async (url, options) => {
            if (url === "/api/csrf") {
                return jsonResponse(200, { headerName: "X-CSRF-TOKEN", token: randomUUID() });
            }
            submittedBody = JSON.parse(options.body).body;
            return jsonResponse(201, { id: 10, title: "문의", status: "OPEN" });
        },
        view
    });
    await client.createTicket("문의", original);
    assert.equal(submittedBody, original);
    assert.equal(view.states.at(-1).kind, "created");
});

test("NBSP는 Java strip처럼 문자 수에 포함한다", async () => {
    let calls = 0;
    const view = recordView();
    const client = createTicketClient({
        fetchImpl: async () => { calls++; throw new Error("must not be called"); },
        view
    });
    await client.createTicket("문의", "\u00a0" + "a".repeat(2000));
    assert.equal(calls, 0);
    assert.equal(view.states.at(-1).kind, "invalid-body");
});
