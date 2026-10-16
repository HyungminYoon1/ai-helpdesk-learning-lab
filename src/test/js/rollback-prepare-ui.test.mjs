import assert from "node:assert/strict";
import { readFileSync, readdirSync } from "node:fs";
import { URL } from "node:url";
import test from "node:test";
import {
    createAiSuggestionClient, createAiSuggestionView, isAiSuggestionQuery
} from "../../main/resources/static/ai-suggestion-ui.mjs";

const failed = (code) => ({
    ticketId: 7, job: { id: 90, status: "FAILED", failureCode: code }, suggestion: null
});

for (const code of ["PRIVACY_SCAN_TIMEOUT", "PRIVACY_SCAN_INVALID_RESULT"]) {
    test(`prepare reader preserves ${code} as a failed job without retrying`, async () => {
        const elements = Object.fromEntries([
            "statusElement", "resultElement", "suggestionElement", "ticketIdElement",
            "jobStatusElement", "failureCodeElement", "summaryElement", "categoriesElement",
            "priorityElement", "reviewStatusElement"
        ].map((name) => [name, {
            textContent: "old content", hidden: false,
            set innerHTML(value) { throw new Error(`HTML assignment of ${typeof value} is forbidden`); }
        }]));
        const calls = [];
        const body = failed(code);
        assert.equal(isAiSuggestionQuery(body, 7), true);
        const client = createAiSuggestionClient({
            fetchImpl: async (url, options) => {
                calls.push({ url, method: options.method });
                return new Response(JSON.stringify(body), {
                    status: 200, headers: { "Content-Type": "application/json" }
                });
            },
            view: createAiSuggestionView(elements)
        });
        await client.readSuggestion(7);
        assert.deepEqual(calls, [{ url: "/api/tickets/7/ai-suggestion", method: "GET" }]);
        assert.equal(elements.jobStatusElement.textContent, "FAILED");
        assert.equal(elements.failureCodeElement.textContent, code);
        assert.equal(elements.statusElement.textContent, "AI 처리가 실패했습니다. 접수한 문의는 유지됩니다.");
        assert.equal(elements.summaryElement.textContent, "");
        assert.equal(elements.suggestionElement.hidden, true);
    });
}

test("prepare reader still rejects unknown codes and extra response fields", () => {
    assert.equal(isAiSuggestionQuery(failed("UNKNOWN_SCAN_FAILURE"), 7), false);
    assert.equal(isAiSuggestionQuery({ ...failed("PRIVACY_SCAN_TIMEOUT"), debug: true }, 7), false);
});

test("prepare production migrations stop at V7; V8 is a separate test-only fixture", () => {
    const production = readdirSync(new URL("../../main/resources/db/migration/", import.meta.url));
    assert.equal(production.length, 7);
    assert.equal(production.some((name) => name.startsWith("V8__")), false);
    const fixture = readFileSync(new URL(
        "../resources/rollback-compatibility/V8__allow_privacy_scan_failures.sql", import.meta.url), "utf8");
    assert.match(fixture, /PRIVACY_SCAN_TIMEOUT/);
    assert.match(fixture, /PRIVACY_SCAN_INVALID_RESULT/);
});
