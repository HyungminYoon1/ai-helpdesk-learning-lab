import assert from "node:assert/strict";
import test from "node:test";
import {
    createAiSuggestionClient, createAiSuggestionView, isAiSuggestionQuery
} from "../../main/resources/static/ai-suggestion-ui.mjs";

function result(status = "SUCCEEDED", ticketId = 7) {
    return {
        ticketId,
        job: status === null ? null : {
            id: 90,
            status,
            failureCode: status === "FAILED" ? "PROVIDER_REFUSED" : null
        },
        suggestion: status === "SUCCEEDED" ? {
            id: 100,
            summary: "새 링크로 로그인에 성공했습니다. 만료 이유를 문의합니다.",
            categories: ["ACCOUNT"],
            priority: "NORMAL",
            reviewStatus: "PENDING_REVIEW"
        } : null
    };
}

function jsonResponse(body) {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: { "Content-Type": "application/json" }
    });
}

function recordView() {
    const states = [];
    return { states, render: (state) => states.push(state) };
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

function viewElements() {
    // DOM Test Double: HTML 대입 시 실패하며 Text 대입과 hidden 변경만 기록한다.
    const element = () => ({
        textContent: "",
        hidden: false,
        set innerHTML(value) { throw new Error(`HTML assignment of ${typeof value} is forbidden`); }
    });
    return Object.fromEntries([
        "statusElement", "resultElement", "suggestionElement", "ticketIdElement",
        "jobStatusElement", "failureCodeElement", "summaryElement", "categoriesElement",
        "priorityElement", "reviewStatusElement"
    ].map((name) => [name, element()]));
}

for (const [status, kind] of [
    [null, "no-job"], ["PENDING", "pending"], ["RUNNING", "running"],
    ["FAILED", "failed"], ["ABSTAINED", "abstained"], ["SUCCEEDED", "succeeded"]
]) {
    test(`HTTP 200의 ${status ?? "Job 부재"}는 ${kind} 화면으로 구분한다`, async () => {
        const body = result(status);
        const view = recordView();
        const response = jsonResponse(body);
        assert.equal(response.ok, true);
        const client = createAiSuggestionClient({ fetchImpl: async () => response, view });
        await client.readSuggestion(7);
        assert.deepEqual(view.states.map((state) => state.kind), ["loading", kind]);
        assert.deepEqual(view.states.at(-1).result, body);
    });
}

test("단순 조회는 Session이 포함될 수 있는 GET 한 건만 보내고 재시도·CSRF 호출은 없다", async () => {
    const calls = [];
    const client = createAiSuggestionClient({
        fetchImpl: async (url, options) => {
            calls.push({ url, options });
            return jsonResponse(result("RUNNING"));
        },
        view: recordView()
    });
    await client.readSuggestion(7);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, "/api/tickets/7/ai-suggestion");
    assert.equal(calls[0].options.method, "GET");
    assert.equal(calls[0].options.credentials, "same-origin");
    assert.equal(calls[0].options.cache, "no-store");
    assert.equal(calls[0].options.headers, undefined);
    assert.equal(calls[0].options.body, undefined);
    assert.equal(calls[0].options.signal.aborted, false);
});

for (const [status, kind] of [
    [400, "bad-request"], [401, "login-required"], [403, "forbidden"],
    [404, "not-found"], [500, "query-error"], [503, "query-error"]
]) {
    test(`조회 HTTP ${status}는 ${kind}이며 JSON 해석·자동 재요청을 하지 않는다`, async () => {
        let calls = 0;
        const view = recordView();
        const client = createAiSuggestionClient({
            fetchImpl: async () => {
                calls++;
                return {
                    status, ok: false,
                    json() { throw new Error("HTTP error body must not be parsed"); }
                };
            },
            view
        });
        await client.readSuggestion(7);
        assert.equal(calls, 1);
        assert.deepEqual(view.states.map((state) => state.kind), ["loading", kind]);
        assert.equal(view.states.at(-1).status, status);
    });
}

test("Response를 받지 못한 조회는 AI Job 실패나 HTTP 500으로 표현하지 않는다", async () => {
    const view = recordView();
    const client = createAiSuggestionClient({
        fetchImpl: async () => { throw new TypeError("response unavailable"); }, view
    });
    await client.readSuggestion(7);
    assert.deepEqual(view.states.map((state) => state.kind), ["loading", "request-unavailable"]);
    assert.equal(view.states.at(-1).result, undefined);
    assert.equal(view.states.at(-1).status, undefined);
});

test("200 응답의 깨진 JSON은 정상 Job이나 제안으로 표시하지 않는다", async () => {
    const view = recordView();
    const client = createAiSuggestionClient({
        fetchImpl: async () => new Response("<html>", { status: 200 }), view
    });
    await client.readSuggestion(7);
    assert.equal(view.states.at(-1).kind, "invalid-response");
});

test("조회 계약은 필드 누락·추가·ID 불일치와 모순된 Job·제안을 거부한다", () => {
    const missing = result();
    delete missing.job;
    const unexpected = { ...result(), rawProviderResponse: "unexpected" };
    const unknownJob = result("UNKNOWN");
    const invalidJobId = result();
    invalidJobId.job.id = "90";
    const previousFailure = result("RUNNING");
    previousFailure.job.failureCode = "PROVIDER_REFUSED";
    const noFailureCode = result("FAILED");
    noFailureCode.job.failureCode = null;
    const rawFailure = result("FAILED");
    rawFailure.job.failureCode = "unlisted provider details";
    const missingSuggestion = result();
    missingSuggestion.suggestion = null;
    const orphan = result(null);
    orphan.suggestion = result().suggestion;
    const failedWithSuggestion = result("FAILED");
    failedWithSuggestion.suggestion = result().suggestion;
    for (const invalid of [null, [], {}, missing, unexpected, result("SUCCEEDED", 8),
        unknownJob, invalidJobId, previousFailure, noFailureCode, rawFailure,
        missingSuggestion, orphan, failedWithSuggestion]) {
        assert.equal(isAiSuggestionQuery(invalid, 7), false);
    }
});

test("제안의 빈 요약·분류 오류·허용값 위반·미지원 검토 상태를 거부한다", () => {
    for (const patch of [
        { summary: "" }, { summary: " \n " }, { summary: null },
        { id: 0 }, { categories: [] }, { categories: ["ACCOUNT", "ACCOUNT"] },
        { categories: ["OTHER_CATEGORY"] }, { categories: "ACCOUNT" },
        { priority: "LOW" }, { reviewStatus: "APPROVED" }, { extra: true }
    ]) {
        const body = result();
        Object.assign(body.suggestion, patch);
        assert.equal(isAiSuggestionQuery(body, 7), false);
    }
});

test("UNDETERMINED와 HTML 모양의 요약은 임의 보정하거나 잘라내지 않는다", () => {
    const body = result();
    body.suggestion.summary = "<strong>긴급</strong>";
    body.suggestion.categories = ["ACCOUNT", "UNDETERMINED"];
    body.suggestion.priority = "UNDETERMINED";
    assert.equal(isAiSuggestionQuery(body, 7), true);
    assert.equal(body.suggestion.summary, "<strong>긴급</strong>");
});

test("잘못된 응답은 Client에서도 제안 표시 전에 거부한다", async () => {
    const body = result();
    body.suggestion.summary = " ";
    const view = recordView();
    const client = createAiSuggestionClient({ fetchImpl: async () => jsonResponse(body), view });
    await client.readSuggestion(7);
    assert.deepEqual(view.states.map((state) => state.kind), ["loading", "invalid-response"]);
});

test("잘못된 ID는 요청을 보내지 않는다", async () => {
    let calls = 0;
    const view = recordView();
    const client = createAiSuggestionClient({
        fetchImpl: async () => { calls++; throw new Error("must not be called"); }, view
    });
    for (const id of [0, -1, 1.5, "7", Number.NaN, Number.MAX_SAFE_INTEGER + 1]) {
        await client.readSuggestion(id);
        assert.equal(view.states.at(-1).kind, "invalid-id");
    }
    assert.equal(calls, 0);
});

test("새 조회는 이전 Signal을 취소하고 늦은 성공을 최신 FAILED 위에 표시하지 않는다", async () => {
    const first = deferred();
    const second = deferred();
    const signals = [];
    const view = recordView();
    const client = createAiSuggestionClient({
        fetchImpl: (url, options) => {
            signals.push(options.signal);
            return url.includes("/1/") ? first.promise : second.promise;
        }, view
    });
    const earlier = client.readSuggestion(1);
    const later = client.readSuggestion(2);
    assert.equal(signals[0].aborted, true);
    second.resolve(jsonResponse(result("FAILED", 2)));
    await later;
    const count = view.states.length;
    first.resolve(jsonResponse(result("SUCCEEDED", 1)));
    await earlier;
    assert.equal(view.states.at(-1).kind, "failed");
    assert.equal(view.states.at(-1).result.ticketId, 2);
    assert.equal(view.states.length, count);
});

test("늦은 조회 실패는 새 ABSTAINED 결과를 덮지 않는다", async () => {
    const first = deferred();
    const view = recordView();
    const client = createAiSuggestionClient({
        fetchImpl: (url) => url.includes("/1/") ? first.promise : jsonResponse(result("ABSTAINED", 2)),
        view
    });
    const earlier = client.readSuggestion(1);
    await client.readSuggestion(2);
    const count = view.states.length;
    first.reject(new TypeError("late failure"));
    await earlier;
    assert.equal(view.states.at(-1).kind, "abstained");
    assert.equal(view.states.length, count);
});

test("JSON 해석 중에 새 조회가 끝나도 이전 Body가 화면을 덮지 않는다", async () => {
    const body = deferred();
    const view = recordView();
    const client = createAiSuggestionClient({
        fetchImpl: async (url) => url.includes("/1/")
            ? { ok: true, json: () => body.promise }
            : jsonResponse(result("RUNNING", 2)),
        view
    });
    const earlier = client.readSuggestion(1);
    await Promise.resolve();
    await client.readSuggestion(2);
    const count = view.states.length;
    body.resolve(result("SUCCEEDED", 1));
    await earlier;
    assert.equal(view.states.at(-1).kind, "running");
    assert.equal(view.states.length, count);
});

test("새 ID가 잘못됐어도 이전 성공을 표시하지 않는다", async () => {
    const first = deferred();
    const view = recordView();
    const client = createAiSuggestionClient({ fetchImpl: async () => first.promise, view });
    const earlier = client.readSuggestion(7);
    await client.readSuggestion(0);
    const count = view.states.length;
    first.resolve(jsonResponse(result()));
    await earlier;
    assert.equal(view.states.at(-1).kind, "invalid-id");
    assert.equal(view.states.length, count);
});

test("AbortError는 조회 오류로 표시하지 않는다", async () => {
    const view = recordView();
    const client = createAiSuggestionClient({
        fetchImpl: async () => {
            const error = new Error("cancelled");
            error.name = "AbortError";
            throw error;
        }, view
    });
    await client.readSuggestion(7);
    assert.deepEqual(view.states.map((state) => state.kind), ["loading"]);
});

test("DOM Text 대입은 요약 문자열을 보존하고 검토 대기로 표시한다", () => {
    const elements = viewElements();
    const view = createAiSuggestionView(elements);
    const body = result();
    body.suggestion.summary = "<strong>긴급</strong>";
    view.render({ kind: "succeeded", result: body });
    assert.equal(elements.summaryElement.textContent, "<strong>긴급</strong>");
    assert.equal(elements.suggestionElement.hidden, false);
    assert.equal(elements.statusElement.textContent, "AI 제안 생성 완료·담당자 검토 대기");
    assert.equal(elements.reviewStatusElement.textContent, "PENDING_REVIEW — 담당자 검토 대기");
});

test("대기·실패·보류·부재와 조회 오류에서는 이전 제안·코드를 지운다", () => {
    const elements = viewElements();
    const view = createAiSuggestionView(elements);
    for (const [status, kind] of [[null, "no-job"], ["PENDING", "pending"],
        ["RUNNING", "running"], ["FAILED", "failed"], ["ABSTAINED", "abstained"]]) {
        view.render({ kind: "succeeded", result: result() });
        view.render({ kind, result: result(status) });
        assert.equal(elements.summaryElement.textContent, "");
        assert.equal(elements.suggestionElement.hidden, true);
        assert.equal(elements.failureCodeElement.textContent, status === "FAILED" ? "PROVIDER_REFUSED" : "없음");
    }
    view.render({ kind: "failed", result: result("FAILED") });
    view.render({ kind: "query-error", status: 500 });
    assert.equal(elements.resultElement.hidden, true);
    assert.equal(elements.failureCodeElement.textContent, "");
    assert.equal(elements.statusElement.textContent, "HTTP 500: AI 상태 조회에 실패했습니다.");
    view.render({ kind: "succeeded", result: result() });
    view.render({ kind: "loading" });
    assert.equal(elements.summaryElement.textContent, "");
    assert.equal(elements.suggestionElement.hidden, true);
});
