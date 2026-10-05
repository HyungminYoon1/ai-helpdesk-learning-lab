import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, existsSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { EVALUATION_CASES } from "../../../scripts/week7-ai-evaluation-dataset.mjs";
import { createDailyLedger, reserveDailyCall, settleDailyCall, withDailyLedger } from "../../../scripts/week7-ai-daily-budget.mjs";
import { buildLivePlan, executeAttempts, inspectEnvelope } from "../../../scripts/week7-ai-live-evaluation.mjs";

// Test doubles check transport and ledger behavior, not real model quality.
function fakePreparation(serialized) {
    const request = JSON.parse(serialized);
    const input = JSON.parse(request.input[0].content);
    const replacements = [["<합성_비밀번호>", "[PASSWORD_REDACTED]"], ["<합성_주소>", "[ADDRESS_REDACTED]"],
        ["<합성_연락처>", "[CONTACT_REDACTED]"]];
    for (const [value, placeholder] of replacements) {
        input.title = input.title.replaceAll(value, placeholder);
        input.body = input.body.replaceAll(value, placeholder);
    }
    request.input[0].content = JSON.stringify(input);
    return JSON.stringify(request);
}

function envelope(extra = {}) {
    return { status: "completed", model: "gpt-6-luna", service_tier: "default",
        usage: { input_tokens: 100, output_tokens: 20 },
        output: [{ type: "message", role: "assistant", content: [{ type: "output_text", text: JSON.stringify({
            decision: "SUGGEST", summary: "새 링크로 로그인에 성공했고 만료 이유를 문의함", categories: ["ACCOUNT"], priority: "NORMAL"
        }) }] }], ...extra };
}

function singleAttemptPlan() {
    const plan = buildLivePlan({ prepareRequest: fakePreparation });
    return { report: { ...plan.report, callsPlanned: 1, attempts: plan.report.attempts.slice(0, 1) }, attempts: plan.attempts.slice(0, 1) };
}

test("privacy plan uses six calls, typed copies, and no evaluator labels in model input", () => {
    const original = JSON.stringify(EVALUATION_CASES);
    const plan = buildLivePlan({ prepareRequest: fakePreparation });
    assert.equal(plan.attempts.length, 6);
    assert.equal(plan.report.callsAttempted, 0);
    assert.equal(plan.report.evidence, "DRY_RUN_NO_API_CALL");
    assert.ok(plan.report.reservedCostUsd > 0 && plan.report.reservedCostUsd < 1);
    for (const attempt of plan.attempts) {
        const input = JSON.parse(JSON.parse(attempt.serializedBody).input[0].content);
        assert.deepEqual(Object.keys(input).sort(), ["body", "title"]);
        assert.equal(attempt.serializedBody.includes("expectedCategories"), false);
        assert.equal(attempt.serializedBody.includes("coreFacts"), false);
    }
    assert.ok(plan.attempts.find(item => item.caseId === "S02").preparedInput.body.includes("[CONTACT_REDACTED]"));
    assert.equal(JSON.stringify(EVALUATION_CASES), original);
});

test("dataset plan keeps all fifty-two comparisons and alternates the second round", () => {
    const plan = buildLivePlan({ experiment: "dataset", prepareRequest: fakePreparation });
    assert.equal(plan.attempts.length, 52);
    assert.equal(plan.report.settings.promptVersion, "prompt-v4-policy-alignment");
    assert.equal(plan.report.settings.inputGuardVersion, "typed-known-markers-v1");
    assert.equal(plan.report.settings.requestSerializationVersion, "title-body-order-v1");
    assert.equal(plan.attempts[0].mode, "prompt-only");
    assert.equal(plan.attempts[26].mode, "structured-output");
});

test("an unprepared copy or changed model setting is rejected before transport", () => {
    assert.throws(() => buildLivePlan({ prepareRequest: value => value }), /SENSITIVE_OR_INVALID/);
    assert.throws(() => buildLivePlan({ prepareRequest: value => {
        const request = JSON.parse(fakePreparation(value));
        request.model = "another-model";
        return JSON.stringify(request);
    } }), /SETTINGS_CHANGED/);
});

test("daily reservation includes known earlier spending and the configured call cap", () => {
    const ledger = createDailyLedger("2026-10-05", 0.9);
    assert.throws(() => reserveDailyCall(ledger, 0.2), /BUDGET_LIMIT/);
    assert.throws(() => createDailyLedger("2026-10-05", undefined), /KNOWN_PRIOR/);
    assert.throws(() => createDailyLedger("2026-02-30", 0), /KNOWN_PRIOR/);
    assert.throws(() => reserveDailyCall({ ...ledger, reservationsMade: 200 }, 0.001), /CALL_OR_BUDGET/);
});

test("unknown usage keeps its reservation and blocks another paid call", () => {
    const pending = reserveDailyCall(createDailyLedger("2026-10-05", 0), 0.02);
    const stopped = settleDailyCall(pending, null);
    assert.equal(stopped.usedEstimatedUsd, 0);
    assert.equal(stopped.heldEstimatedUsd, 0.02);
    assert.equal(stopped.blocked, true);
    assert.throws(() => reserveDailyCall(stopped, 0.01), /RECONCILIATION/);
});

test("an estimate overrun is recorded and blocks further calls", () => {
    const stopped = settleDailyCall(reserveDailyCall(createDailyLedger("2026-10-05", 0), 0.01), 0.02);
    assert.equal(stopped.usedEstimatedUsd, 0.02);
    assert.equal(stopped.stopReason, "COST_ESTIMATE_EXCEEDED");
    assert.equal(stopped.blocked, true);
});

test("the daily ledger survives separate executions without resetting costs", async () => {
    const repositoryRoot = mkdtempSync(join(tmpdir(), "week7-ai-budget-test-"));
    await withDailyLedger({ repositoryRoot, day: "2026-10-05", knownPriorUsd: 0 }, async ({ ledger, persist }) => {
        persist(reserveDailyCall(ledger, 0.02));
        persist(settleDailyCall(reserveDailyCall(ledger, 0.02), 0.001));
    });
    await withDailyLedger({ repositoryRoot, day: "2026-10-05" }, async ({ ledger }) => {
        assert.equal(ledger.usedEstimatedUsd, 0.001);
        assert.equal(ledger.reservationsMade, 1);
    });
    assert.equal(existsSync(join(repositoryRoot, "local", "ai-experiments", "2026-10-05-budget.json.lock")), false);
});

test("a pending reservation after interruption prevents automatic re-execution", async () => {
    const repositoryRoot = mkdtempSync(join(tmpdir(), "week7-ai-budget-pending-test-"));
    await withDailyLedger({ repositoryRoot, day: "2026-10-05", knownPriorUsd: 0 }, async ({ ledger, persist }) => {
        persist(reserveDailyCall(ledger, 0.02));
    });
    await assert.rejects(withDailyLedger({ repositoryRoot, day: "2026-10-05" }, async () => {}), /RECONCILIATION/);
    const stored = JSON.parse(readFileSync(join(repositoryRoot, "local", "ai-experiments", "2026-10-05-budget.json"), "utf8"));
    assert.equal(stored.pendingReservationUsd, 0.02);
});

test("reservation is persisted before the exact checked body reaches the transport", async () => {
    const plan = singleAttemptPlan();
    let saved;
    let calls = 0;
    const result = await executeAttempts({ plan, ledger: createDailyLedger("2026-10-05", 0),
        persist: value => { saved = value; }, apiKey: "synthetic-test-only-credential", fetchImpl: async (url, options) => {
            calls += 1;
            assert.equal(saved.pendingReservationUsd, plan.attempts[0].reservationUsd);
            assert.equal(url, "https://api.openai.com/v1/responses");
            assert.equal(options.body, plan.attempts[0].serializedBody);
            assert.equal(options.redirect, "error");
            return new Response(JSON.stringify(envelope()));
        } });
    assert.equal(calls, 1);
    assert.equal(result.results[0].outcome, "VALID_OUTPUT");
    assert.equal(result.results[0].assessment.manualSummaryReview, "NOT_SCORED");
    assert.equal(saved.pendingReservationUsd, null);
});

test("a reservation storage failure leads to zero transport calls", async () => {
    let calls = 0;
    await assert.rejects(executeAttempts({ plan: singleAttemptPlan(), ledger: createDailyLedger("2026-10-05", 0),
        persist: () => { throw new Error("SIMULATED_STORAGE_FAILURE"); }, apiKey: "synthetic-test-only-credential",
        fetchImpl: async () => { calls += 1; } }), /SIMULATED_STORAGE_FAILURE/);
    assert.equal(calls, 0);
});

test("a body changed after checking leads to zero transport calls", async () => {
    const plan = singleAttemptPlan();
    plan.attempts[0].serializedBody += " ";
    let calls = 0;
    await assert.rejects(executeAttempts({ plan, ledger: createDailyLedger("2026-10-05", 0), persist: () => {},
        apiKey: "synthetic-test-only-credential", fetchImpl: async () => { calls += 1; } }), /PREPARED_REQUEST_CHANGED/);
    assert.equal(calls, 0);
});

test("an unknown transport outcome retains the reservation and does not retry", async () => {
    let calls = 0;
    const result = await executeAttempts({ plan: singleAttemptPlan(), ledger: createDailyLedger("2026-10-05", 0),
        persist: () => {}, apiKey: "synthetic-test-only-credential", fetchImpl: async () => {
            calls += 1;
            throw new Error("simulated transport failure");
        } });
    assert.equal(calls, 1);
    assert.equal(result.stopped, true);
    assert.equal(result.dailyLedger.heldEstimatedUsd, singleAttemptPlan().attempts[0].reservationUsd);
    assert.equal(result.dailyLedger.blocked, true);
});

test("refusal and undecodable envelopes are not treated as valid abstention", () => {
    const refusal = inspectEnvelope("N01", envelope({ output: [{ type: "message", role: "assistant", content: [{ type: "refusal" }] }] }));
    assert.equal(refusal.outcome, "PROVIDER_REFUSAL");
    assert.equal(Object.hasOwn(refusal, "outputText"), false);
    assert.equal(inspectEnvelope("N01", null).outcome, "PROVIDER_NOT_COMPLETED");
});

test("JSON-escaped synthetic markers in output are detected after decoding", () => {
    const marked = envelope();
    marked.output[0].content[0].text = JSON.stringify({ decision: "SUGGEST", summary: "<합성_비밀번호>", categories: ["ACCOUNT"], priority: "NORMAL" })
        .replace("<", "\\" + "u003c");
    assert.equal(inspectEnvelope("S01", marked).outcome, "SYNTHETIC_MARKER_IN_OUTPUT");
});

test("an unknown model or service tier cannot be assigned a fabricated cost", () => {
    assert.equal(inspectEnvelope("N01", envelope({ model: "other-model" })).estimatedCostUsd, null);
    assert.equal(inspectEnvelope("N01", envelope({ service_tier: "priority" })).estimatedCostUsd, null);
});
