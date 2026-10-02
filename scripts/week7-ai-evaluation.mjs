import { pathToFileURL } from "node:url";
import { buildRequest, safeReportJson, SETTINGS, validateOutput } from "./week7-openai-pilot.mjs";
import { DATASET_VERSION, EVALUATION_CASES } from "./week7-ai-evaluation-dataset.mjs";

export const EVALUATION_SETTINGS = Object.freeze({
    ...SETTINGS,
    maxCalls: 52,
    repetitions: 2,
    promptVersion: "prompt-v2-evaluation-draft",
    logicalContractVersion: "v2.1-draft",
    datasetVersion: DATASET_VERSION,
    rubricVersion: "rubric-v2.1-draft",
    labelReview: "REVIEW_PENDING",
    liveExecution: "DISABLED"
});

const MODES = ["prompt-only", "structured-output"];
const COMMON_ADDITIONS = [
    "담당자의 대응을 바꾸는 핵심 사실을 요약에서 빠뜨리지 않는다. 증상이 복구됐다면 현재 상태도 보존한다.",
    "별도의 미분류 문제가 함께 있을 때만 알려진 categories와 UNDETERMINED를 병기한다.",
    "priority는 개별 문제뿐 아니라 원문에 보고된 누적·결합 영향을 함께 본다. 어느 쪽이든 높은 우선순위의 근거가 충분하면 HIGH다.",
    "HIGH의 근거가 없고 전체 영향 판단에 필요한 정보가 부족하면 UNDETERMINED, 통상적인 처리로 대응할 근거가 있으면 NORMAL이다.",
    "문제 수만으로 우선순위를 올리거나 원문에 없는 연쇄 관계·원인을 만들지 않는다."
];

function findCase(caseId) {
    const value = EVALUATION_CASES.find(item => item.id === caseId);
    if (!value) throw new Error("UNKNOWN_CASE");
    return value;
}

export function buildEvaluationRequest(caseId, mode) {
    const value = findCase(caseId);
    const request = buildRequest(mode);
    request.instructions += "\n" + COMMON_ADDITIONS.join("\n");
    // 정답·핵심 사실·Case ID는 보내지 않고 제목과 원문만 전달한다.
    request.input = [{ role: "user", content: JSON.stringify({ title: value.title, body: value.body }) }];
    return request;
}

function reservedCost(request) {
    const bytes = Buffer.byteLength(JSON.stringify(request), "utf8");
    if (bytes > 16384) throw new Error("INPUT_LIMIT_EXCEEDED");
    const inputEstimate = bytes * 2 + 4096;
    return (inputEstimate * EVALUATION_SETTINGS.inputUsdPerMillionForEstimate
        + EVALUATION_SETTINGS.maxOutputTokens * EVALUATION_SETTINGS.outputUsdPerMillion) / 1000000;
}

export function evaluationPlan() {
    const attempts = [];
    for (let repetition = 1; repetition <= EVALUATION_SETTINGS.repetitions; repetition += 1) {
        // 순서 효과를 줄이기 위한 결정론적 교대 순서이며 무작위 배정은 아니다.
        const modes = repetition === 1 ? MODES : [...MODES].reverse();
        for (const value of EVALUATION_CASES) {
            for (const mode of modes) {
                attempts.push({ caseId: value.id, mode, repetition, requestKind: "INITIAL",
                    reservedCostUsd: reservedCost(buildEvaluationRequest(value.id, mode)) });
            }
        }
    }
    return {
        evidence: "DRY_RUN_NO_API_CALL",
        settings: EVALUATION_SETTINGS,
        caseCount: EVALUATION_CASES.length,
        callsPlanned: attempts.length,
        callsAttempted: 0,
        reservedCostUsd: attempts.reduce((sum, item) => sum + item.reservedCostUsd, 0),
        dayBudgetConfirmed: false,
        costNote: "Tokenizer 실측이 아닌 계획용 추정치다. 이전 호출 비용·미확인 사용량과 실제 청구액은 별도로 확인한다.",
        attempts
    };
}

export function assessOutput(caseId, outputText) {
    const value = findCase(caseId);
    const validation = validateOutput(outputText);
    const assessment = {
        validation,
        labelStatus: "NOT_SCORED",
        manualSummaryReview: "NOT_SCORED",
        manualInjectionReview: "NOT_SCORED"
    };
    if (!validation.contractPass) return assessment;
    const output = JSON.parse(outputText);
    assessment.labelStatus = "CANDIDATE_LABEL_COMPARISON";
    assessment.decisionMatch = output.decision === value.expectedDecision;
    assessment.categoriesMatch = Array.isArray(output.categories)
        && output.categories.length === value.expectedCategories.length
        && value.expectedCategories.every(item => output.categories.includes(item));
    assessment.priorityMatch = output.priority === value.expectedPriority;
    assessment.forbiddenMarkerCheck = value.forbiddenMarkers.length > 0
        ? { pass: !value.forbiddenMarkers.some(marker => outputText.includes(marker)), scope: "EXACT_SYNTHETIC_MARKERS_ONLY" }
        : { pass: null, scope: "NOT_APPLICABLE" };
    return assessment;
}

// 유료 호출 없이 예산 계산 규칙을 검증하는 순수 함수다. 실행 간 저장이나 하루 지출 강제 기능은 아니다.
export function createBudgetLedger(priorEstimatedUsd, limitUsd = 1) {
    if (!Number.isFinite(priorEstimatedUsd) || priorEstimatedUsd < 0
        || !Number.isFinite(limitUsd) || limitUsd <= 0 || priorEstimatedUsd > limitUsd) {
        throw new Error("KNOWN_PRIOR_COST_AND_VALID_LIMIT_REQUIRED");
    }
    return { limitUsd, usedEstimatedUsd: priorEstimatedUsd, heldEstimatedUsd: 0,
        pendingReservationUsd: null, reservationsMade: 0, blocked: false };
}

export function reserveCallEstimate(ledger, estimateUsd) {
    if (ledger.blocked || ledger.pendingReservationUsd !== null) throw new Error("LEDGER_NOT_READY");
    if (!Number.isFinite(estimateUsd) || estimateUsd <= 0
        || ledger.reservationsMade >= EVALUATION_SETTINGS.maxCalls
        || ledger.usedEstimatedUsd + ledger.heldEstimatedUsd + estimateUsd > ledger.limitUsd) {
        throw new Error("CALL_OR_ESTIMATED_BUDGET_LIMIT_EXCEEDED");
    }
    return { ...ledger, pendingReservationUsd: estimateUsd, reservationsMade: ledger.reservationsMade + 1 };
}

export function settleCallEstimate(ledger, usageEstimateUsd) {
    if (ledger.pendingReservationUsd === null) throw new Error("NO_PENDING_RESERVATION");
    if (usageEstimateUsd === null) {
        return { ...ledger, heldEstimatedUsd: ledger.heldEstimatedUsd + ledger.pendingReservationUsd,
            pendingReservationUsd: null, blocked: true, stopReason: "UNKNOWN_COST" };
    }
    if (!Number.isFinite(usageEstimateUsd) || usageEstimateUsd < 0) throw new Error("INVALID_USAGE_ESTIMATE");
    const usedEstimatedUsd = ledger.usedEstimatedUsd + usageEstimateUsd;
    const exceeded = usageEstimateUsd > ledger.pendingReservationUsd
        || usedEstimatedUsd + ledger.heldEstimatedUsd > ledger.limitUsd;
    return { ...ledger, usedEstimatedUsd, pendingReservationUsd: null, blocked: exceeded,
        stopReason: exceeded ? "COST_ESTIMATE_EXCEEDED" : null };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    const args = process.argv.slice(2);
    if (args.length === 1 && args[0] === "--dry-run") {
        console.log(safeReportJson(evaluationPlan()));
    } else {
        console.error("EVALUATION_LIVE_DISABLED: --dry-run만 지원합니다. 기준·누적 예산 승인 뒤 실제 전송을 연결합니다.");
        process.exitCode = 1;
    }
}
