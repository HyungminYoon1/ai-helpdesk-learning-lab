import test from "node:test";
import assert from "node:assert/strict";
import { buildRequest, pilotPlan, runPilot, safeReportJson, validateOutput } from "../../../scripts/week7-openai-pilot.mjs";

const TEST_KEY = "synthetic-test-credential";
const suggestion = {
    decision: "SUGGEST",
    summary: "로그인 링크 만료 후 새 링크로 로그인했으며 만료 이유를 문의함",
    categories: ["ACCOUNT"],
    priority: "NORMAL"
};

function envelope(value = suggestion) {
    return {
        status: "completed",
        model: "gpt-6-luna",
        output: [{
            type: "message", role: "assistant",
            content: [{ type: "output_text", text: JSON.stringify(value) }]
        }],
        usage: { input_tokens: 500, output_tokens: 80 }
    };
}

function reply(value) {
    return new Response(JSON.stringify(value), { status: 200 });
}

test("both modes use the same input and instructions and differ only in text.format", () => {
    const plain = buildRequest("prompt-only");
    const structured = buildRequest("structured-output");
    assert.deepEqual({ ...plain, text: undefined }, { ...structured, text: undefined });
    assert.equal(plain.text.format.type, "text");
    assert.equal(structured.text.format.type, "json_schema");
    assert.equal(structured.text.format.strict, true);
    assert.equal(plain.store, false);
    assert.equal(plain.service_tier, "default");
    assert.equal(plain.reasoning.effort, "none");
    assert.equal(Object.hasOwn(plain, "tools"), false);
});

test("dry-run plan contains only N01, two calls, bounded output and an estimate below one dollar", () => {
    const plan = pilotPlan();
    assert.equal(plan.caseId, "N01");
    assert.equal(plan.settings.maxCalls, 2);
    assert.equal(plan.settings.maxOutputTokens, 600);
    assert.equal(plan.modes.length, 2);
    assert.ok(plan.reservedCostUsd > 0 && plan.reservedCostUsd < 1);
});

test("contract accepts a valid suggestion and the tentative ABSTAIN shape", () => {
    assert.equal(validateOutput(JSON.stringify(suggestion)).contractPass, true);
    assert.equal(validateOutput(JSON.stringify({
        decision: "ABSTAIN", summary: null, categories: null, priority: null
    })).contractPass, true);
});

test("contract rejects malformed JSON, missing and extra fields and contradictory branches", () => {
    assert.equal(validateOutput("not JSON").jsonPass, false);
    for (const value of [
        null, [],
        { ...suggestion, category: "ACCOUNT" },
        { decision: "SUGGEST", summary: "title", categories: ["ACCOUNT"] },
        { ...suggestion, decision: "UNKNOWN" },
        { ...suggestion, decision: "ABSTAIN" },
        { ...suggestion, priority: null },
        { ...suggestion, summary: null }
    ]) {
        assert.equal(validateOutput(JSON.stringify(value)).contractPass, false);
    }
});

test("contract rejects blank, overlong and invalid category values without normalizing them", () => {
    for (const summary of ["", "   ", "가".repeat(201)]) {
        assert.equal(validateOutput(JSON.stringify({ ...suggestion, summary })).contractPass, false);
    }
    for (const categories of [[], "ACCOUNT", ["ACCOUNT", "ACCOUNT"], ["UNKNOWN"], null]) {
        assert.equal(validateOutput(JSON.stringify({ ...suggestion, categories })).contractPass, false);
    }
    assert.equal(validateOutput(JSON.stringify({ ...suggestion, priority: "URGENT" })).contractPass, false);
});

test("summary length uses trimmed Unicode code points rather than UTF-16 code units", () => {
    assert.equal(validateOutput(JSON.stringify({ ...suggestion, summary: "😀".repeat(200) })).contractPass, true);
    assert.equal(validateOutput(JSON.stringify({ ...suggestion, summary: "😀".repeat(201) })).contractPass, false);
    assert.equal(validateOutput(JSON.stringify({ ...suggestion, summary: " " + "가".repeat(200) + " " })).contractPass, true);
});

test("synthetic transport makes exactly two attempts without exposing the key in model input or reports", async () => {
    const requests = [];
    const report = await runPilot({ apiKey: TEST_KEY, fetchImpl: async (url, options) => {
        requests.push(options);
        assert.equal(url, "https://api.openai.com/v1/responses");
        assert.equal(options.redirect, "error");
        assert.equal(options.body.includes(TEST_KEY), false);
        return reply(envelope());
    } });
    assert.equal(requests.length, 2);
    assert.equal(report.callsAttempted, 2);
    assert.equal(report.stopped, false);
    assert.deepEqual(report.remainingModes, []);
    assert.ok(report.results.every(result => result.validation.contractPass));
    assert.ok(report.estimatedTotalCostUsd > 0);
    assert.equal(safeReportJson(report, TEST_KEY).includes(TEST_KEY), false);
});

test("HTTP error stops at the first attempt and never reads or logs the error body", async () => {
    let attempts = 0;
    const report = await runPilot({ apiKey: TEST_KEY, fetchImpl: async () => {
        attempts += 1;
        return { status: 401, ok: false, json() { throw new Error("error body must not be read"); } };
    } });
    assert.equal(attempts, 1);
    assert.equal(report.stopped, true);
    assert.equal(report.results[0].outcome, "HTTP_ERROR");
    assert.equal(report.estimatedTotalCostUsd, null);
    assert.deepEqual(report.remainingModes, ["structured-output"]);
});

test("transport failure has unknown provider outcome, is not retried, and hides exception contents", async () => {
    let attempts = 0;
    const report = await runPilot({ apiKey: TEST_KEY, fetchImpl: async () => {
        attempts += 1;
        throw new Error(TEST_KEY);
    } });
    assert.equal(attempts, 1);
    assert.equal(report.results[0].outcome, "TRANSPORT_FAILURE_OUTCOME_UNKNOWN");
    assert.equal(JSON.stringify(report).includes(TEST_KEY), false);
});

test("refusal and incomplete provider responses are separate from an invalid model JSON result", async () => {
    for (const value of [
        { ...envelope(), status: "incomplete" },
        { ...envelope(), output: [{ type: "message", role: "assistant", content: [{ type: "refusal" }] }] }
    ]) {
        const report = await runPilot({ apiKey: TEST_KEY, fetchImpl: async () => reply(value) });
        assert.equal(report.callsAttempted, 1);
        assert.equal(report.stopped, true);
        assert.ok(["PROVIDER_NOT_COMPLETED", "PROVIDER_REFUSAL"].includes(report.results[0].outcome));
        assert.equal(report.results[0].outputText, null);
    }
});

test("invalid completed output is preserved as a failure without repair or another request", async () => {
    const report = await runPilot({ apiKey: TEST_KEY, fetchImpl: async () => reply(envelope({ ...suggestion, summary: " " })) });
    assert.equal(report.callsAttempted, 1);
    assert.equal(report.results[0].outcome, "INVALID_OUTPUT");
    assert.equal(report.results[0].validation.jsonPass, true);
    assert.equal(report.results[0].validation.contractPass, false);
});

test("bad envelope and missing model text stop without inventing a suggestion", async () => {
    const transports = [
        async () => new Response("not JSON", { status: 200 }),
        async () => reply({ ...envelope(), output: [] })
    ];
    for (const fetchImpl of transports) {
        const report = await runPilot({ apiKey: TEST_KEY, fetchImpl });
        assert.equal(report.callsAttempted, 1);
        assert.ok(["ENVELOPE_READ_FAILED", "MODEL_TEXT_MISSING"].includes(report.results[0].outcome));
    }
});

test("missing usage is unknown cost, not zero cost, and prevents the second call", async () => {
    const report = await runPilot({ apiKey: TEST_KEY, fetchImpl: async () => reply({ ...envelope(), usage: null }) });
    assert.equal(report.callsAttempted, 1);
    assert.equal(report.results[0].outcome, "USAGE_UNAVAILABLE");
    assert.equal(report.estimatedTotalCostUsd, null);
});

test("a key echoed in synthetic output is redacted before display", () => {
    const display = safeReportJson({ outputText: TEST_KEY }, TEST_KEY);
    assert.equal(display.includes(TEST_KEY), false);
    assert.ok(display.includes("[REDACTED]"));
});

test("missing credentials fail before any transport invocation", async () => {
    let attempts = 0;
    await assert.rejects(runPilot({ apiKey: undefined, fetchImpl: async () => { attempts += 1; } }), /API_KEY_MISSING/);
    assert.equal(attempts, 0);
});
