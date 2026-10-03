import test from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { fileURLToPath, URL } from "node:url";
import { EVALUATION_CASES } from "../../../scripts/week7-ai-evaluation-dataset.mjs";
import { assessOutput, buildEvaluationRequest, createBudgetLedger, evaluationPlan,
    reserveCallEstimate, settleCallEstimate } from "../../../scripts/week7-ai-evaluation.mjs";

function outputFor(caseId, extra = {}) {
    const value = EVALUATION_CASES.find(item => item.id === caseId);
    return JSON.stringify({ decision: "SUGGEST", summary: "합성 응답. 요약 내용은 별도로 평가한다.",
        categories: value.expectedCategories, priority: value.expectedPriority, ...extra });
}

test("evaluation plan preserves all thirteen cases and fifty-two independent initial attempts", () => {
    const plan = evaluationPlan();
    assert.equal(EVALUATION_CASES.length, 13);
    assert.equal(new Set(EVALUATION_CASES.map(item => item.id)).size, 13);
    assert.equal(plan.callsPlanned, 52);
    assert.equal(plan.callsAttempted, 0);
    assert.equal(plan.evidence, "DRY_RUN_NO_API_CALL");
    assert.equal(plan.settings.liveExecution, "DISABLED");
    assert.equal(plan.dayBudgetConfirmed, false);
    assert.ok(plan.reservedCostUsd > 0 && plan.reservedCostUsd < 1);
    assert.equal(new Set(plan.attempts.map(item => JSON.stringify([item.caseId, item.mode, item.repetition]))).size, 52);
    assert.ok(plan.attempts.every(item => item.requestKind === "INITIAL"));
});

test("each case appears twice per mode and order alternates without being called random", () => {
    const plan = evaluationPlan();
    for (const value of EVALUATION_CASES) {
        for (const mode of ["prompt-only", "structured-output"]) {
            assert.equal(plan.attempts.filter(item => item.caseId === value.id && item.mode === mode).length, 2);
        }
    }
    assert.equal(plan.attempts[0].mode, "prompt-only");
    assert.equal(plan.attempts[26].mode, "structured-output");
});

test("only title and body are sent and both modes receive identical common instructions", () => {
    for (const value of EVALUATION_CASES) {
        const plain = buildEvaluationRequest(value.id, "prompt-only");
        const structured = buildEvaluationRequest(value.id, "structured-output");
        assert.deepEqual({ ...plain, text: undefined }, { ...structured, text: undefined });
        assert.deepEqual(JSON.parse(plain.input[0].content), { title: value.title, body: value.body });
        assert.equal(plain.input[0].content.includes("expectedCategories"), false);
        assert.equal(plain.input[0].content.includes("coreFacts"), false);
        assert.ok(plain.instructions.includes("누적·결합 영향"));
        assert.ok(plain.instructions.includes("핵심 사실"));
        assert.equal(Object.hasOwn(plain, "tools"), false);
    }
});

test("injection instructions remain user data rather than trusted instructions", () => {
    const request = buildEvaluationRequest("I02", "structured-output");
    assert.ok(JSON.parse(request.input[0].content).body.includes("9999"));
    assert.equal(request.instructions.includes("9999"), false);
    assert.deepEqual(request.text.format.schema.required, ["decision", "summary", "categories", "priority"]);
    assert.equal(request.text.format.schema.additionalProperties, false);
});

test("both modes receive the same explicit abstain boundary without changing the fixed dataset", () => {
    const plain = buildEvaluationRequest("A01", "prompt-only");
    const structured = buildEvaluationRequest("A01", "structured-output");
    assert.equal(plain.instructions, structured.instructions);
    assert.ok(plain.instructions.includes("문의 의미를 해석할 수 없어 유효한 요약 자체를 만들 수 없을 때만 ABSTAIN"));
    assert.ok(plain.instructions.includes("정보가 적어도 요청의 의미를 이해하고 확인한 사실을 요약할 수 있으면 SUGGEST"));
    assert.ok(plain.instructions.includes("입력의 길이·오타·언어만으로 ABSTAIN을 결정"));
    assert.equal(evaluationPlan().settings.promptVersion, "prompt-v3-abstain-draft");
    assert.equal(evaluationPlan().caseCount, 13);
    assert.equal(evaluationPlan().callsPlanned, 52);
});

test("a structurally valid abstain does not satisfy A01's meaningful-request expectation", () => {
    const result = assessOutput("A01", JSON.stringify({
        decision: "ABSTAIN", summary: null, categories: null, priority: null
    }));
    assert.equal(result.validation.contractPass, true);
    assert.equal(result.decisionMatch, false);
    assert.equal(result.manualSummaryReview, "NOT_SCORED");
});

test("unknown cases and modes are rejected", () => {
    assert.throws(() => buildEvaluationRequest("UNKNOWN", "prompt-only"), /UNKNOWN_CASE/);
    assert.throws(() => buildEvaluationRequest("N01", "UNKNOWN"), /INVALID_MODE/);
});

test("category comparison ignores order but keeps omission and extra-category failures", () => {
    assert.equal(assessOutput("M01", outputFor("M01", { categories: ["BILLING", "ACCOUNT"] })).categoriesMatch, true);
    assert.equal(assessOutput("M01", outputFor("M01", { categories: ["ACCOUNT"] })).categoriesMatch, false);
    assert.equal(assessOutput("N01", outputFor("N01", { categories: ["ACCOUNT", "TECHNICAL"] })).categoriesMatch, false);
});

test("OTHER and insufficient information remain distinct draft expectations", () => {
    assert.equal(assessOutput("N04", outputFor("N04")).categoriesMatch, true);
    assert.equal(assessOutput("N04", outputFor("N04", { categories: ["UNDETERMINED"] })).categoriesMatch, false);
    assert.equal(assessOutput("A01", outputFor("A01")).categoriesMatch, true);
    assert.equal(assessOutput("A01", outputFor("A01", { categories: ["OTHER"] })).categoriesMatch, false);
    assert.equal(assessOutput("N04", outputFor("N04")).labelStatus, "CANDIDATE_LABEL_COMPARISON");
});

test("schema failures are preserved and are not assigned a fabricated content score", () => {
    for (const output of ["not JSON", outputFor("N01", { summary: " " }), outputFor("I02", { ticketId: 9999 })]) {
        const result = assessOutput("N01", output);
        assert.equal(result.validation.contractPass, false);
        assert.equal(result.labelStatus, "NOT_SCORED");
        assert.equal(result.manualSummaryReview, "NOT_SCORED");
        assert.equal(Object.hasOwn(result, "summaryScore"), false);
    }
});

test("matching labels do not certify a summary that contradicts the source", () => {
    const result = assessOutput("N01", outputFor("N01", { summary: "새 링크를 받아도 여전히 로그인할 수 없습니다." }));
    assert.equal(result.validation.contractPass, true);
    assert.equal(result.categoriesMatch, true);
    assert.equal(result.priorityMatch, true);
    assert.equal(result.manualSummaryReview, "NOT_SCORED");
    assert.equal(result.manualInjectionReview, "NOT_SCORED");
});

test("sensitive-output check is limited to exact synthetic markers", () => {
    const result = assessOutput("S01", outputFor("S01", { summary: "<합성_비밀번호>로 로그인할 수 없다고 문의함" }));
    assert.equal(result.validation.contractPass, true);
    assert.equal(result.forbiddenMarkerCheck.pass, false);
    assert.equal(result.forbiddenMarkerCheck.scope, "EXACT_SYNTHETIC_MARKERS_ONLY");
    assert.equal(assessOutput("S02", outputFor("S02")).forbiddenMarkerCheck.pass, true);
    assert.equal(assessOutput("N01", outputFor("N01")).forbiddenMarkerCheck.pass, null);
});

test("budget calculations include previous costs and reject unknown or invalid baselines", () => {
    for (const prior of [undefined, null, NaN, -1, 2]) {
        assert.throws(() => createBudgetLedger(prior), /KNOWN_PRIOR_COST/);
    }
    const original = createBudgetLedger(0.8);
    const settled = settleCallEstimate(reserveCallEstimate(original, 0.15), 0.1);
    assert.equal(original.usedEstimatedUsd, 0.8);
    assert.equal(settled.usedEstimatedUsd, 0.9);
    assert.throws(() => reserveCallEstimate(settled, 0.15), /BUDGET_LIMIT_EXCEEDED/);
});

test("unknown usage keeps the reservation and blocks further requests instead of counting zero cost", () => {
    const settled = settleCallEstimate(reserveCallEstimate(createBudgetLedger(0), 0.02), null);
    assert.equal(settled.heldEstimatedUsd, 0.02);
    assert.equal(settled.blocked, true);
    assert.equal(settled.stopReason, "UNKNOWN_COST");
    assert.throws(() => reserveCallEstimate(settled, 0.01), /LEDGER_NOT_READY/);
});

test("overrun and pending reservations block another request and the call cap is enforced", () => {
    const pending = reserveCallEstimate(createBudgetLedger(0), 0.01);
    assert.throws(() => reserveCallEstimate(pending, 0.01), /LEDGER_NOT_READY/);
    const overrun = settleCallEstimate(pending, 0.02);
    assert.equal(overrun.usedEstimatedUsd, 0.02);
    assert.equal(overrun.blocked, true);
    assert.throws(() => settleCallEstimate(createBudgetLedger(0), 0), /NO_PENDING_RESERVATION/);
    assert.throws(() => reserveCallEstimate({ ...createBudgetLedger(0), reservationsMade: 52 }, 0.01), /CALL_OR_ESTIMATED/);
});

test("CLI supports only dry-run and never activates a live transport", () => {
    const script = new URL("../../../scripts/week7-ai-evaluation.mjs", import.meta.url);
    const dry = spawnSync(process.execPath, [fileURLToPath(script), "--dry-run"], { encoding: "utf8" });
    assert.equal(dry.status, 0);
    assert.equal(JSON.parse(dry.stdout).callsAttempted, 0);
    const live = spawnSync(process.execPath, [fileURLToPath(script), "--live"], { encoding: "utf8" });
    assert.equal(live.status, 1);
    assert.ok(live.stderr.includes("EVALUATION_LIVE_DISABLED"));
});
