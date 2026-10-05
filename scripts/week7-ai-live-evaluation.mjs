import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join, delimiter } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { isDeepStrictEqual } from "node:util";
import { buildEvaluationRequest, evaluationPlan, assessOutput, EVALUATION_SETTINGS } from "./week7-ai-evaluation.mjs";
import { safeReportJson } from "./week7-openai-pilot.mjs";
import { withDailyLedger, reserveDailyCall, settleDailyCall } from "./week7-ai-daily-budget.mjs";

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)));
const MAX_BYTES = 16384;
const MARKERS = ["<합성_비밀번호>", "<합성_주소>", "<합성_연락처>", "<합성_이메일>", "<합성_휴대폰>", "<합성_인스타그램>"];

function javaEnvironment() {
    const result = {};
    for (const name of ["PATH", "JAVA_HOME", "SYSTEMROOT", "TEMP", "TMP"]) {
        if (process.env[name]) result[name] = process.env[name];
    }
    return result;
}

export function prepareWithJava(serializedRequest) {
    const classpathFile = join(ROOT, "target", "week7-java-classpath.txt");
    if (!existsSync(classpathFile)) throw new Error("JAVA_BRIDGE_NOT_PREPARED");
    const dependencies = readFileSync(classpathFile, "utf8").trim();
    const classpath = [join(ROOT, "target", "test-classes"), join(ROOT, "target", "classes"), dependencies].join(delimiter);
    const binary = process.env.JAVA_HOME ? join(process.env.JAVA_HOME, "bin", process.platform === "win32" ? "java.exe" : "java") : "java";
    const child = spawnSync(binary, ["-cp", classpath, "lab.helpdesk.ai.input.AiInputPrivacyRequestBridge"], {
        cwd: ROOT, env: javaEnvironment(), input: serializedRequest, encoding: "utf8",
        maxBuffer: 65536, timeout: 15000, windowsHide: true
    });
    // Child diagnostics are deliberately not included in errors or reports.
    if (child.error || child.status !== 0 || !child.stdout) throw new Error("JAVA_PRIVACY_BRIDGE_REJECTED");
    return child.stdout;
}

function hasMarker(value) {
    if (typeof value === "string") return MARKERS.some(marker => value.includes(marker));
    if (Array.isArray(value)) return value.some(hasMarker);
    if (value && typeof value === "object") return Object.entries(value).some(([name, item]) => hasMarker(name) || hasMarker(item));
    return false;
}

function inspectPreparedBody(original, serializedBody) {
    if (typeof serializedBody !== "string" || Buffer.byteLength(serializedBody, "utf8") > MAX_BYTES) {
        throw new Error("PREPARED_REQUEST_INVALID");
    }
    const prepared = JSON.parse(serializedBody);
    if (!isDeepStrictEqual({ ...original, input: null }, { ...prepared, input: null })
        || !Array.isArray(prepared.input) || prepared.input.length !== 1 || prepared.input[0].role !== "user") {
        throw new Error("PREPARED_REQUEST_SETTINGS_CHANGED");
    }
    const preparedInput = JSON.parse(prepared.input[0].content);
    if (hasMarker(prepared) || hasMarker(preparedInput)
        || typeof preparedInput.title !== "string" || !preparedInput.title.trim()
        || typeof preparedInput.body !== "string" || !preparedInput.body.trim()) {
        throw new Error("PREPARED_REQUEST_SENSITIVE_OR_INVALID");
    }
    return preparedInput;
}

export function buildLivePlan({ experiment = "privacy", prepareRequest = prepareWithJava } = {}) {
    const specifications = experiment === "privacy"
        ? ["N01", "S01", "S02"].flatMap(caseId => ["prompt-only", "structured-output"].map(mode => ({ caseId, mode, repetition: 1 })))
        : experiment === "dataset" ? evaluationPlan().attempts : null;
    if (!specifications) throw new Error("INVALID_EXPERIMENT");
    const attempts = specifications.map(({ caseId, mode, repetition }) => {
        const request = buildEvaluationRequest(caseId, mode);
        const serializedBody = prepareRequest(JSON.stringify(request));
        const preparedInput = inspectPreparedBody(request, serializedBody);
        const bytes = Buffer.byteLength(serializedBody, "utf8");
        // Conservative input rate also covers the documented cache-write rate.
        const reservationUsd = ((bytes * 2 + 4096) * EVALUATION_SETTINGS.inputUsdPerMillionForEstimate
            + EVALUATION_SETTINGS.maxOutputTokens * EVALUATION_SETTINGS.outputUsdPerMillion) / 1000000;
        return { caseId, mode, repetition, serializedBody, preparedInput, bytes, reservationUsd,
            requestSha256: createHash("sha256").update(serializedBody, "utf8").digest("hex") };
    });
    const report = {
        evidence: "DRY_RUN_NO_API_CALL", experiment,
        settings: { ...EVALUATION_SETTINGS, inputGuardVersion: "typed-known-markers-v1",
            requestSerializationVersion: "title-body-order-v1", liveExecution: "EXPERIMENT_ONLY" },
        callsPlanned: attempts.length, callsAttempted: 0,
        reservedCostUsd: attempts.reduce((sum, item) => sum + item.reservationUsd, 0),
        costNote: "사용량 기반 보수적 추정치다. 이 실행기 외의 호출과 실제 청구액은 별도로 확인한다.",
        attempts: attempts.map(({ caseId, mode, repetition, preparedInput, bytes, reservationUsd, requestSha256 }) => ({
            caseId, mode, repetition, preparedInput, bytes, reservationUsd, requestSha256,
            inputCheck: "JAVA_GUARD_PASSED_KNOWN_SYNTHETIC_MARKERS_ABSENT"
        }))
    };
    return { report, attempts };
}

function usageFrom(envelope) {
    const usage = envelope?.usage;
    if (!Number.isSafeInteger(usage?.input_tokens) || usage.input_tokens < 0
        || !Number.isSafeInteger(usage?.output_tokens) || usage.output_tokens < 0) return null;
    return { inputTokens: usage.input_tokens, outputTokens: usage.output_tokens };
}

export function inspectEnvelope(caseId, envelope) {
    const usage = usageFrom(envelope);
    const priced = usage && typeof envelope?.model === "string" && /^gpt-6-luna(?:-\d{4}-\d{2}-\d{2})?$/.test(envelope.model)
        && envelope.service_tier === "default";
    const result = { usage, returnedModel: typeof envelope?.model === "string" ? envelope.model : null,
        returnedServiceTier: typeof envelope?.service_tier === "string" ? envelope.service_tier : null,
        estimatedCostUsd: priced ? (usage.inputTokens * EVALUATION_SETTINGS.inputUsdPerMillionForEstimate
            + usage.outputTokens * EVALUATION_SETTINGS.outputUsdPerMillion) / 1000000 : null };
    if (envelope?.status !== "completed" || envelope.error) return { ...result, outcome: "PROVIDER_NOT_COMPLETED" };
    const content = Array.isArray(envelope.output) ? envelope.output
        .filter(item => item?.type === "message" && item.role === "assistant")
        .flatMap(item => Array.isArray(item.content) ? item.content : []) : [];
    if (content.some(item => item?.type === "refusal")) return { ...result, outcome: "PROVIDER_REFUSAL" };
    const outputText = content.filter(item => item?.type === "output_text" && typeof item.text === "string").map(item => item.text).join("");
    if (!outputText) return { ...result, outcome: "MODEL_TEXT_MISSING" };
    const assessment = assessOutput(caseId, outputText);
    let outputMarkerPass = !hasMarker(outputText);
    if (assessment.validation.jsonPass) outputMarkerPass = !hasMarker(JSON.parse(outputText));
    return { ...result, outputText, assessment, outputMarkerPass,
        outcome: !assessment.validation.contractPass ? "INVALID_OUTPUT"
            : !outputMarkerPass ? "SYNTHETIC_MARKER_IN_OUTPUT"
                : result.estimatedCostUsd === null ? "USAGE_OR_TARIFF_UNAVAILABLE" : "VALID_OUTPUT" };
}

export async function executeAttempts({ plan, ledger, persist, apiKey, fetchImpl, onProgress = () => {} }) {
    if (typeof apiKey !== "string" || !apiKey.trim()) throw new Error("API_KEY_MISSING");
    if (typeof fetchImpl !== "function" || typeof persist !== "function") throw new Error("TRANSPORT_AND_LEDGER_REQUIRED");
    const report = { ...plan.report, evidence: "PROVIDER_CALL_ATTEMPT", day: ledger.day,
        dayBudgetUsd: ledger.limitUsd, priorEstimatedUsd: ledger.usedEstimatedUsd, results: [], stopped: false };
    let current = ledger;
    for (const attempt of plan.attempts) {
        const checkedBody = attempt.serializedBody;
        if (typeof checkedBody !== "string" || checkedBody.includes(apiKey)) throw new Error("CREDENTIAL_IN_MODEL_INPUT");
        if (createHash("sha256").update(checkedBody, "utf8").digest("hex") !== attempt.requestSha256) {
            throw new Error("PREPARED_REQUEST_CHANGED");
        }
        current = reserveDailyCall(current, attempt.reservationUsd);
        await persist(current); // A failed reservation write means zero HTTP calls.
        onProgress({ event: "CALL_START", caseId: attempt.caseId, mode: attempt.mode, repetition: attempt.repetition });
        const start = performance.now();
        report.callsAttempted += 1;
        let result = { caseId: attempt.caseId, mode: attempt.mode, repetition: attempt.repetition,
            requestSha256: attempt.requestSha256, preparedInput: attempt.preparedInput, estimatedCostUsd: null };
        try {
            const response = await fetchImpl("https://api.openai.com/v1/responses", {
                method: "POST", redirect: "error",
                headers: { "Content-Type": "application/json", Authorization: `Bearer ${apiKey}` },
                body: checkedBody,
                signal: AbortSignal.timeout(EVALUATION_SETTINGS.timeoutMs)
            });
            result.httpStatus = response.status;
            if (!response.ok) {
                result.outcome = "HTTP_ERROR";
            } else {
                try {
                    result = { ...result, ...inspectEnvelope(attempt.caseId, await response.json()) };
                } catch {
                    result.outcome = "ENVELOPE_READ_FAILED";
                }
            }
        } catch {
            result.outcome = "TRANSPORT_FAILURE_OUTCOME_UNKNOWN";
        }
        result.latencyMs = Math.round(performance.now() - start);
        current = settleDailyCall(current, result.estimatedCostUsd);
        await persist(current);
        report.results.push(result);
        onProgress({ event: "CALL_END", caseId: result.caseId, mode: result.mode, outcome: result.outcome });
        if (current.blocked || result.outcome !== "VALID_OUTPUT") {
            report.stopped = true;
            report.stopReason = current.stopReason ?? result.outcome;
            break;
        }
    }
    report.dailyLedger = current;
    report.remainingCalls = plan.attempts.length - report.callsAttempted;
    return report;
}

function argumentsFor(args) {
    const config = { live: false, dryRun: false, keyConfirmed: false, syntheticConfirmed: false, experiment: "privacy" };
    const seen = new Set();
    for (let index = 0; index < args.length; index += 1) {
        const flag = args[index];
        if (seen.has(flag)) throw new Error("INVALID_ARGUMENTS");
        seen.add(flag);
        if (flag === "--live") config.live = true;
        else if (flag === "--dry-run") config.dryRun = true;
        else if (flag === "--confirm-helpdesk-key") config.keyConfirmed = true;
        else if (flag === "--confirm-synthetic") config.syntheticConfirmed = true;
        else if (["--experiment", "--day", "--budget-usd", "--prior-estimated-usd"].includes(flag)) {
            const value = args[++index];
            if (!value || value.startsWith("--")) throw new Error("INVALID_ARGUMENTS");
            if (flag === "--experiment") config.experiment = value;
            else if (flag === "--day") config.day = value;
            else if (flag === "--budget-usd") config.budgetUsd = Number(value);
            else config.knownPriorUsd = Number(value);
        } else throw new Error("INVALID_ARGUMENTS");
    }
    if (config.live === config.dryRun) throw new Error("EXPLICIT_MODE_REQUIRED");
    return config;
}

async function main() {
    try {
        const config = argumentsFor(process.argv.slice(2));
        if (config.live && (!config.keyConfirmed || !config.syntheticConfirmed || config.budgetUsd !== 1)) {
            throw new Error("LIVE_APPROVAL_FLAGS_REQUIRED");
        }
        const today = new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Seoul", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());
        if (config.live && config.day !== today) throw new Error("BUDGET_APPROVAL_DATE_MISMATCH");
        const apiKey = config.live ? process.env.OPENAI_API_KEY : null;
        if (config.live && (typeof apiKey !== "string" || !apiKey.trim())) throw new Error("API_KEY_MISSING");
        const plan = buildLivePlan({ experiment: config.experiment });
        if (config.dryRun) {
            console.log(safeReportJson(plan.report));
            return;
        }
        const report = await withDailyLedger({ repositoryRoot: ROOT, day: config.day,
            limitUsd: config.budgetUsd, knownPriorUsd: config.knownPriorUsd }, async ({ ledger, persist }) => {
            if (ledger.usedEstimatedUsd + ledger.heldEstimatedUsd + plan.report.reservedCostUsd > ledger.limitUsd) {
                throw new Error("DAILY_CALL_OR_BUDGET_LIMIT_EXCEEDED");
            }
            return executeAttempts({ plan, ledger, persist, apiKey, fetchImpl: globalThis.fetch,
                onProgress: event => console.log(safeReportJson(event, apiKey)) });
        });
        const safeResult = safeReportJson(report, apiKey);
        const fileName = `${config.day}-${config.experiment}-${Date.now()}.json`;
        writeFileSync(join(ROOT, "local", "ai-experiments", fileName), safeResult + "\n", "utf8");
        console.log(safeResult);
        console.log(JSON.stringify({ savedReport: `local/ai-experiments/${fileName}` }));
        if (report.stopped) process.exitCode = 1;
    } catch (error) {
        const safeCodes = new Set(["JAVA_BRIDGE_NOT_PREPARED", "JAVA_PRIVACY_BRIDGE_REJECTED", "INVALID_ARGUMENTS", "EXPLICIT_MODE_REQUIRED",
            "LIVE_APPROVAL_FLAGS_REQUIRED", "BUDGET_APPROVAL_DATE_MISMATCH", "API_KEY_MISSING", "INVALID_EXPERIMENT",
            "KNOWN_PRIOR_COST_AND_VALID_BUDGET_REQUIRED", "DAILY_LEDGER_INVALID", "PRIOR_COST_ONLY_ON_NEW_LEDGER",
            "BUDGET_RECONCILIATION_REQUIRED", "DAILY_CALL_OR_BUDGET_LIMIT_EXCEEDED", "DAILY_BUDGET_LOCK_UNAVAILABLE",
            "PREPARED_REQUEST_INVALID", "PREPARED_REQUEST_SETTINGS_CHANGED", "PREPARED_REQUEST_SENSITIVE_OR_INVALID"]);
        safeCodes.add("CREDENTIAL_IN_MODEL_INPUT");
        safeCodes.add("PREPARED_REQUEST_CHANGED");
        console.error(safeCodes.has(error?.message) ? error.message : "EXPERIMENT_NOT_STARTED_OR_STOPPED");
        process.exitCode = 1;
    }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await main();
