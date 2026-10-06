import { spawnSync } from "node:child_process";
import { writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { withDailyLedger, reserveDailyCall, settleDailyCall } from "./week7-ai-daily-budget.mjs";
import { RESERVATION_USD, scopedChildEnvironment, runMavenPreflight, windowsShell } from "./week7-java-provider-live.mjs";
import { safeReportJson } from "./week7-openai-pilot.mjs";

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)));
const PREFIX = "HELPDESK_WORKER_BROWSER_EVIDENCE ";
const CHECKS = ["browserE2e", "workerScheduled", "originalPreserved", "authorFromAuthentication",
    "databaseAndUiAgree", "readOnlyQueryPreserved", "sessionCookieObserved", "csrfHeaderObserved"];

export function workerBrowserPlan() {
    return { evidence: "DRY_RUN_NO_API_CALL", callsPlanned: 1, callsAttempted: 0,
        model: "gpt-6-luna", protocol: "chat-completions", database: "isolated PostgreSQL 17.6 Testcontainer",
        steps: ["Browser USER login", "missing-CSRF POST 403", "Session + CSRF receipt 201",
            "scheduled Worker", "AI + validation + PostgreSQL", "Browser AGENT read-only UI"],
        original: "synthetic login-recovery inquiry only", manualContentReview: "NOT_SCORED",
        experimentJobGenerationLimit: 1, globalJobPolicyChanged: false,
        costLimitWaiverRequiresExplicitDailyApproval: true, keepsExistingUsageLedger: true };
}

export function readWorkerBrowserEvidence(stdout, live) {
    const lines = typeof stdout === "string" ? stdout.split(/\r?\n/).filter(line => line.startsWith(PREFIX)) : [];
    let raw;
    try {
        if (lines.length !== 1 || lines[0].length > 4096) throw new Error();
        raw = JSON.parse(lines[0].slice(PREFIX.length));
    } catch { throw new Error("WORKER_BROWSER_EVIDENCE_INVALID"); }
    const expected = live ? "LIVE_WORKER_BROWSER_POSTGRES" : "CONTROLLED_WORKER_BROWSER_POSTGRES";
    if (!raw || raw.evidence !== expected || raw.manualContentReview !== "NOT_SCORED"
        || raw.protocol !== (live ? "chat-completions" : "test-double")
        || !["SUCCEEDED", "FAILED", "ABSTAINED", "PENDING", "RUNNING", "EXPERIMENT_FAILED"].includes(raw.jobStatus)
        || !Number.isInteger(raw.httpAttempts) || raw.httpAttempts < 0 || raw.httpAttempts > (live ? 1 : 0)
        || typeof raw.expectedTariffConfirmed !== "boolean") throw new Error("WORKER_BROWSER_EVIDENCE_INVALID");
    const evidence = { evidence: expected, protocol: raw.protocol, manualContentReview: "NOT_SCORED",
        jobStatus: raw.jobStatus, httpAttempts: raw.httpAttempts,
        expectedTariffConfirmed: raw.expectedTariffConfirmed, usage: null };
    if (["SETUP", "POSTGRES_START", "APPLICATION_START", "BROWSER_RECEIPT", "WORKER_RESULT",
        "BROWSER_AGENT_READ", "COMPLETED"].includes(raw.experimentStage)) evidence.experimentStage = raw.experimentStage;
    if (typeof raw.failureType === "string" && /^[A-Za-z]{1,80}$/.test(raw.failureType)) evidence.failureType = raw.failureType;
    if (typeof raw.browserDiagnostic === "string" && /^(OPEN|ANONYMOUS_READ|USER_LOGIN|RECEIPT_POST|AGENT_LOGIN|AGENT_READ) (UNKNOWN|SyntaxError|TimeoutError|TypeError|ReferenceError|LOGIN_FAILED|SECURITY_CONTROL_FAILED|RECEIPT_FAILED)$/.test(raw.browserDiagnostic)) {
        evidence.browserDiagnostic = raw.browserDiagnostic;
    }
    if (raw.httpStatus === null || Number.isInteger(raw.httpStatus) && raw.httpStatus >= 100 && raw.httpStatus <= 599) {
        evidence.httpStatus = raw.httpStatus;
    }
    for (const name of CHECKS) {
        if (typeof raw[name] === "boolean") evidence[name] = raw[name];
    }
    for (const name of ["receiptStatus", "missingCsrfStatus", "anonymousQueryStatus", "userQueryStatus",
        "agentQueryStatus", "ticketId", "reservedGenerationCount", "suggestionCount", "categoryCount", "providerInvocations"]) {
        if (Number.isSafeInteger(raw[name]) && raw[name] >= 0) evidence[name] = raw[name];
    }
    if (raw.ticketStatus === "OPEN") evidence.ticketStatus = "OPEN";
    if (raw.reviewStatus === "PENDING_REVIEW") evidence.reviewStatus = "PENDING_REVIEW";
    if (raw.usage && Number.isSafeInteger(raw.usage.inputTokens) && raw.usage.inputTokens >= 0
        && Number.isSafeInteger(raw.usage.outputTokens) && raw.usage.outputTokens >= 0) {
        evidence.usage = { inputTokens: raw.usage.inputTokens, outputTokens: raw.usage.outputTokens };
    }
    if (typeof raw.screenshot === "string"
        && /^output\/playwright\/week7-worker-[a-z0-9-]+\/agent-suggestion\.png$/.test(raw.screenshot)) {
        evidence.screenshot = raw.screenshot;
    }
    return evidence;
}

export function workerBrowserCompleted(report, live) {
    return report.javaExitSucceeded === true && CHECKS.every(name => report[name] === true)
        && report.jobStatus === "SUCCEEDED" && report.ticketStatus === "OPEN" && report.reviewStatus === "PENDING_REVIEW"
        && report.receiptStatus === 201 && report.missingCsrfStatus === 403
        && report.anonymousQueryStatus === 401 && report.userQueryStatus === 403 && report.agentQueryStatus === 200
        && report.providerInvocations === 1 && report.reservedGenerationCount === 1 && report.suggestionCount === 1
        && report.categoryCount >= 1 && report.httpAttempts === (live ? 1 : 0);
}

function runJavaBrowser({ live, apiKey, day }) {
    const testName = live ? "SpringAiOpenAiWorkerBrowserLiveExperiment" : "AiSuggestionWorkerBrowserSmokeExperiment";
    const env = scopedChildEnvironment(live ? { apiKey, day, priorCostUnconfirmed: true } : {});
    if (live) env.HELPDESK_AI_COST_LIMIT_WAIVER_CONFIRMED = "true";
    const child = spawnSync(windowsShell(), ["-NoProfile", "-NonInteractive", "-Command",
        `.\\mvnw.cmd -q '-Dtest=${testName}' test; exit $LASTEXITCODE`], {
        cwd: ROOT, env, encoding: "utf8", timeout: 360000, maxBuffer: 2097152, windowsHide: true
    });
    return { ...readWorkerBrowserEvidence(child.stdout, live), javaExitSucceeded: !child.error && child.status === 0 };
}

export async function executeWorkerBrowser({ repositoryRoot = ROOT, day, apiKey, confirmCostLimitWaiver = false,
    runPreflight = runMavenPreflight, runJava = runJavaBrowser, persistReport = () => {} }) {
    if (typeof apiKey !== "string" || !apiKey.trim()) throw new Error("HELPDESK_SCOPED_KEY_REQUIRED");
    if (!confirmCostLimitWaiver) throw new Error("EXPLICIT_DAILY_COST_WAIVER_REQUIRED");
    const preflight = await runPreflight();
    if (preflight?.passed !== true || preflight.credentialsPassed !== false) throw new Error("MAVEN_STARTUP_PRECHECK_FAILED");
    return withDailyLedger({ repositoryRoot, day, proceedWithUnknownPrior: true, confirmCostLimitWaiver },
        async ({ ledger, persist }) => {
            let current = reserveDailyCall(ledger, RESERVATION_USD);
            persist(current);
            let evidence;
            try { evidence = await runJava({ live: true, apiKey, day }); } catch {
                persist(settleDailyCall(current, null));
                throw new Error("WORKER_BROWSER_OUTCOME_UNKNOWN");
            }
            const usage = evidence.usage;
            const cost = evidence.httpAttempts === 0 ? 0 : usage && evidence.expectedTariffConfirmed
                ? (usage.inputTokens * 0.125 + usage.outputTokens * 0.50) / 1000000 : null;
            current = settleDailyCall(current, cost);
            persist(current);
            const report = { ...evidence, day, costLimitEnforced: false, estimatedCostUsd: cost,
                dailyEstimateComplete: ledger.priorCostUnconfirmed !== true,
                dailyEstimatedTotalUsd: ledger.priorCostUnconfirmed ? null : current.usedEstimatedUsd,
                dailyLedger: current, completed: workerBrowserCompleted(evidence, true) };
            persistReport(report);
            return report;
        });
}

async function main() {
    try {
        const args = process.argv.slice(2);
        if (args.length === 1 && args[0] === "--dry-run") { console.log(safeReportJson(workerBrowserPlan())); return; }
        if (args.length === 1 && args[0] === "--smoke") {
            runMavenPreflight();
            const report = runJavaBrowser({ live: false });
            report.completed = workerBrowserCompleted(report, false);
            console.log(safeReportJson(report));
            if (!report.completed) process.exitCode = 1;
            return;
        }
        const today = new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Seoul",
            year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());
        const expected = ["--live", "--confirm-helpdesk-key", "--confirm-synthetic", "--confirm-cost-limit-waiver", "--day", today];
        if (args.length !== expected.length || args.some((value, index) => value !== expected[index])) {
            throw new Error("EXPLICIT_DAILY_LIVE_APPROVAL_REQUIRED");
        }
        const apiKey = process.env.HELPDESK_OPENAI_API_KEY;
        const report = await executeWorkerBrowser({ day: today, apiKey, confirmCostLimitWaiver: true,
            persistReport: value => writeFileSync(join(ROOT, "local", "ai-experiments",
                `${today}-worker-browser-${Date.now()}.json`), safeReportJson(value, apiKey) + "\n", "utf8") });
        console.log(safeReportJson(report, apiKey));
        if (!report.completed) process.exitCode = 1;
    } catch (error) {
        const safeCodes = new Set(["HELPDESK_SCOPED_KEY_REQUIRED", "EXPLICIT_DAILY_COST_WAIVER_REQUIRED",
            "EXPLICIT_DAILY_LIVE_APPROVAL_REQUIRED", "MAVEN_STARTUP_PRECHECK_FAILED", "WORKER_BROWSER_EVIDENCE_INVALID",
            "WORKER_BROWSER_OUTCOME_UNKNOWN", "BUDGET_RECONCILIATION_REQUIRED", "DAILY_BUDGET_LOCK_UNAVAILABLE",
            "DAILY_LEDGER_INVALID", "WINDOWS_EXPERIMENT_RUNNER_REQUIRED"]);
        console.error(safeCodes.has(error?.message) ? error.message : "WORKER_BROWSER_EXPERIMENT_STOPPED");
        process.exitCode = 1;
    }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await main();
