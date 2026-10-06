import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { URL } from "node:url";

test("Page Script의 모든 Selector와 Module 경로가 실제 HTML에 연결된다", async () => {
    const root = new URL("../../main/resources/static/", import.meta.url);
    const html = await readFile(new URL("ai-suggestions.html", root), "utf8");
    const script = await readFile(new URL("ai-suggestion-page.mjs", root), "utf8");
    const selectors = [...script.matchAll(/querySelector\("#([^"]+)"\)/g)];
    assert.equal(selectors.length, 12);
    for (const [, id] of selectors) {
        assert.ok(html.includes(`id="${id}"`));
    }
    assert.ok(html.includes('type="module" src="/ai-suggestion-page.mjs"'));
    assert.ok(script.includes('from "./ai-suggestion-ui.mjs"'));
    assert.ok(html.includes('id="ai-suggestion" aria-labelledby="ai-suggestion-heading" hidden'));
    assert.ok(html.includes("공식 답변이나 확정된 사실이 아닙니다."));
});

test("Submit 연결은 Page 이동 대신 GET 조회와 검토 대기 Text 표시를 실행한다", { timeout: 2000 }, async () => {
    const documentBefore = Object.getOwnPropertyDescriptor(globalThis, "document");
    const windowBefore = Object.getOwnPropertyDescriptor(globalThis, "window");
    let onSubmit;
    let completed;
    const rendered = new Promise((resolve) => { completed = resolve; });
    const calls = [];
    const elements = new Map();
    for (const id of ["ai-query-form", "ai-ticket-id-input", "ai-ui-status", "ai-result",
        "ai-suggestion", "ai-ticket-id", "ai-job-status", "ai-failure-code", "ai-summary",
        "ai-categories", "ai-priority", "ai-review-status"]) {
        elements.set(`#${id}`, { textContent: "", hidden: false });
    }
    elements.get("#ai-ticket-id-input").value = "7";
    elements.get("#ai-query-form").addEventListener = (name, listener) => {
        assert.equal(name, "submit");
        onSubmit = listener;
    };
    Object.defineProperty(elements.get("#ai-review-status"), "textContent", {
        set(value) {
            if (value === "PENDING_REVIEW — 담당자 검토 대기") {
                completed();
            }
        }
    });

    try {
        globalThis.document = {
            querySelector(selector) {
                assert.ok(elements.has(selector));
                return elements.get(selector);
            }
        };
        globalThis.window = {
            async fetch(url, options) {
                calls.push({ url, options });
                return {
                    ok: true,
                    async json() {
                        return {
                            ticketId: 7,
                            job: { id: 90, status: "SUCCEEDED", failureCode: null },
                            suggestion: {
                                id: 100, summary: "로그인 오류", categories: ["ACCOUNT"],
                                priority: "UNDETERMINED", reviewStatus: "PENDING_REVIEW"
                            }
                        };
                    }
                };
            }
        };
        await import("../../main/resources/static/ai-suggestion-page.mjs?wiring-test");
        assert.equal(calls.length, 0);
        let prevented = false;
        onSubmit({ preventDefault() { prevented = true; } });
        await rendered;
        assert.equal(prevented, true);
        assert.equal(calls.length, 1);
        assert.equal(calls[0].url, "/api/tickets/7/ai-suggestion");
        assert.equal(calls[0].options.method, "GET");
        assert.equal(elements.get("#ai-ui-status").textContent, "AI 제안 생성 완료·담당자 검토 대기");
        assert.equal(elements.get("#ai-summary").textContent, "로그인 오류");
        assert.equal(elements.get("#ai-suggestion").hidden, false);
    } finally {
        for (const [name, descriptor] of [["document", documentBefore], ["window", windowBefore]]) {
            if (descriptor === undefined) {
                delete globalThis[name];
            } else {
                Object.defineProperty(globalThis, name, descriptor);
            }
        }
    }
});
