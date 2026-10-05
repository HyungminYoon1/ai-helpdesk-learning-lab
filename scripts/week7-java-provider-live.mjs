import { spawnSync } from "node:child_process";
import { existsSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { withDailyLedger, reserveDailyCall, settleDailyCall } from "./week7-ai-daily-budget.mjs";
import { safeReportJson } from "./week7-openai-pilot.mjs";

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)));
const PREFIX = "HELPDESK_JAVA_LIVE_EVIDENCE ";
// Match the Java transport's 32 KiB request and 600 Token output caps.
export const RESERVATION_USD = ((32768 * 2 + 4096) * 0.125 + 600 * 0.50) / 1000000;
const OUTCOMES = new Set(["NO_JOB", "STORED", "ABSTAINED", "NOT_CURRENT", "OUTPUT_REPAIR_SCHEDULED",
    "FAILED", "PROVIDER_OUTCOME_UNKNOWN", "STORAGE_PENDING", "LIVE_FAILED"]);
const KINDS = new Set(["REFUSED", "OUTCOME_UNKNOWN", "CONFIGURATION", "INVALID_RESPONSE", "TEMPORARY_REJECTION"]);

export function livePlan() {
    return { evidence: "DRY_RUN_NO_API_CALL", protocol: "chat-completions", model: "gpt-6-luna",
        callsPlanned: 1, callsAttempted: 0, reservedCostUsd: RESERVATION_USD,
        input: "synthetic login-recovery inquiry with a synthetic email marker",
        database: "isolated PostgreSQL 17.6 Testcontainer", manualContentReview: "NOT_SCORED",
        browserE2e: false, sharedDailyLedger: true,
        costNote: "보수적 사용량 추정치이며 계정의 실제 청구 한도는 아니다. 미확인 비용은 0으로 처리하지 않는다." };
}

export function readSafeJavaEvidence(stdout) {
    if (typeof stdout !== "string") throw new Error("JAVA_EVIDENCE_MISSING_OR_INVALID");
    const lines = stdout.split(/\r?\n/).filter(line => line.startsWith(PREFIX));
    if (lines.length !== 1 || lines[0].length > 4096) throw new Error("JAVA_EVIDENCE_MISSING_OR_INVALID");
    let raw;
    try { raw = JSON.parse(lines[0].slice(PREFIX.length)); } catch { throw new Error("JAVA_EVIDENCE_MISSING_OR_INVALID"); }
    if (!raw || raw.evidence !== "LIVE_JAVA_SPRING_AI_POSTGRES"
        || raw.caseId !== "SYNTHETIC_LOGIN_RECOVERY" || raw.protocol !== "chat-completions"
        || raw.manualContentReview !== "NOT_SCORED" || raw.browserE2e !== false
        || !OUTCOMES.has(raw.outcome) || ![0, 1].includes(raw.httpAttempts)
        || !(raw.httpStatus === null || Number.isInteger(raw.httpStatus) && raw.httpStatus >= 100 && raw.httpStatus <= 599)
        || typeof raw.expectedTariffConfirmed !== "boolean") throw new Error("JAVA_EVIDENCE_MISSING_OR_INVALID");
    // Reconstruct only safe, expected fields. Never forward arbitrary child diagnostics.
    const evidence = { evidence: raw.evidence, caseId: raw.caseId, protocol: raw.protocol,
        manualContentReview: raw.manualContentReview, browserE2e: false, outcome: raw.outcome,
        httpAttempts: raw.httpAttempts, httpStatus: raw.httpStatus, expectedTariffConfirmed: raw.expectedTariffConfirmed,
        usage: null };
    if (raw.usage && Number.isSafeInteger(raw.usage.inputTokens) && raw.usage.inputTokens >= 0
        && Number.isSafeInteger(raw.usage.outputTokens) && raw.usage.outputTokens >= 0) {
        evidence.usage = { inputTokens: raw.usage.inputTokens, outputTokens: raw.usage.outputTokens };
    }
    if (KINDS.has(raw.providerFailureKind)) evidence.providerFailureKind = raw.providerFailureKind;
    for (const name of ["originalPreserved", "jobSucceeded", "databaseInspectionFailed"]) {
        if (typeof raw[name] === "boolean") evidence[name] = raw[name];
    }
    for (const name of ["reservedGenerationCount", "suggestionCount", "categoryCount"]) {
        if (Number.isSafeInteger(raw[name]) && raw[name] >= 0) evidence[name] = raw[name];
    }
    return evidence;
}

export function scopedChildEnvironment({ apiKey, day, priorCostUnconfirmed, launcherRecoveryConfirmed } = {},
    hostEnvironment = process.env) {
    const environment = {};
    for (const name of ["PATH", "PATHEXT", "COMSPEC", "JAVA_HOME", "SYSTEMROOT", "WINDIR", "TEMP", "TMP",
        "USERPROFILE", "HOMEDRIVE", "HOMEPATH"]) {
        if (hostEnvironment[name]) environment[name] = hostEnvironment[name];
    }
    // The generic OPENAI_API_KEY, other projects' credentials and custom Maven options are excluded.
    if (apiKey === undefined) return environment;
    return { ...environment, HELPDESK_OPENAI_API_KEY: apiKey,
        HELPDESK_AI_LIVE_CONFIRMED: "true", HELPDESK_AI_LIVE_DAY: day,
        HELPDESK_AI_PRIOR_COST_UNCONFIRMED: String(priorCostUnconfirmed),
        HELPDESK_AI_LAUNCHER_RECOVERY_CONFIRMED: String(launcherRecoveryConfirmed) };
}

function windowsShell(hostEnvironment = process.env) {
    if (process.platform !== "win32") throw new Error("WINDOWS_EXPERIMENT_RUNNER_REQUIRED");
    const modernShell = "C:\\Program Files\\WindowsApps\\Microsoft.PowerShell_7.6.6.0_x64__8wekyb3d8bbwe\\pwsh.exe";
    return existsSync(modernShell) ? modernShell
        : join(hostEnvironment.SYSTEMROOT, "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
}

export function runMavenPreflight({ spawnProcess = spawnSync, hostEnvironment = process.env } = {}) {
    const environment = scopedChildEnvironment({}, hostEnvironment);
    if (!environment.PATHEXT || !environment.COMSPEC) throw new Error("MAVEN_STARTUP_PRECHECK_FAILED");
    const child = spawnProcess(windowsShell(hostEnvironment), ["-NoProfile", "-NonInteractive", "-Command",
        ".\\mvnw.cmd --version; exit $LASTEXITCODE"], {
        cwd: ROOT, env: environment, encoding: "utf8", timeout: 60000, maxBuffer: 1048576, windowsHide: true
    });
    if (child.error || child.status !== 0 || !/Apache Maven 3\.9\.16\b/.test(child.stdout ?? "")
        || !/Java version: 25[.]/.test(child.stdout ?? "")) throw new Error("MAVEN_STARTUP_PRECHECK_FAILED");
    return { evidence: "MAVEN_PREFLIGHT_NO_API_CALL", passed: true, credentialsPassed: false };
}

function runJavaExperiment({ apiKey, day, priorCostUnconfirmed, launcherRecoveryConfirmed }) {
    const shell = windowsShell();
    // Static command: no key or user-provided text is interpolated into the command line.
    const child = spawnSync(shell, ["-NoProfile", "-NonInteractive", "-Command",
        ".\\mvnw.cmd -q '-Dtest=SpringAiOpenAiSuggestionProviderLiveExperiment' test; exit $LASTEXITCODE"], {
        cwd: ROOT, env: scopedChildEnvironment({ apiKey, day, priorCostUnconfirmed, launcherRecoveryConfirmed }), encoding: "utf8",
        timeout: 240000, maxBuffer: 1048576, windowsHide: true
    });
    return { ...readSafeJavaEvidence(child.stdout), javaExitSucceeded: !child.error && child.status === 0 };
}

export async function executeLiveExperiment({ repositoryRoot, day, knownPriorUsd, apiKey,
    proceedWithUnknownPrior = false, recoverLauncherOnce = false, runPreflight = runMavenPreflight,
    runJava = runJavaExperiment, persistReport = () => {} }) {
    if (typeof apiKey !== "string" || !apiKey.trim()) throw new Error("HELPDESK_SCOPED_KEY_REQUIRED");
    const preflight = await runPreflight();
    if (preflight?.evidence !== "MAVEN_PREFLIGHT_NO_API_CALL" || preflight.passed !== true
        || preflight.credentialsPassed !== false) throw new Error("MAVEN_STARTUP_PRECHECK_FAILED");
    return withDailyLedger({ repositoryRoot, day, limitUsd: 1, knownPriorUsd, proceedWithUnknownPrior,
        recoverLauncherOnce, recoveryReservationUsd: RESERVATION_USD }, async ({ ledger, persist }) => {
        let current = reserveDailyCall(ledger, RESERVATION_USD);
        persist(current); // Must be durable before Java is allowed to call the Provider.
        let evidence;
        try {
            evidence = await runJava({ apiKey, day, priorCostUnconfirmed: ledger.priorCostUnconfirmed === true,
                launcherRecoveryConfirmed: ledger.launcherRecovery !== undefined });
        } catch {
            current = settleDailyCall(current, null);
            persist(current);
            throw new Error("JAVA_OUTCOME_OR_USAGE_UNKNOWN_RECONCILE_BUDGET");
        }
        const usage = evidence.usage;
        const cost = evidence.httpAttempts === 0 ? 0
            : usage && evidence.expectedTariffConfirmed
                ? (usage.inputTokens * 0.125 + usage.outputTokens * 0.50) / 1000000 : null;
        current = settleDailyCall(current, cost);
        persist(current);
        const priorCostUnconfirmed = ledger.priorCostUnconfirmed === true;
        const report = { ...evidence, day,
            priorEstimatedUsd: priorCostUnconfirmed ? null : ledger.usedEstimatedUsd,
            dailyEstimateComplete: !priorCostUnconfirmed,
            dailyEstimatedTotalUsd: priorCostUnconfirmed ? null : current.usedEstimatedUsd,
            budgetScope: priorCostUnconfirmed ? "TRACKED_RUNNER_CALLS_ONLY" : "DECLARED_PRIOR_AND_TRACKED_CALLS",
            estimatedCostUsd: cost, dailyLedger: current,
            completed: evidence.javaExitSucceeded && evidence.outcome === "STORED"
                && evidence.httpAttempts === 1 && evidence.reservedGenerationCount === 1
                && evidence.originalPreserved === true && evidence.jobSucceeded === true
                && evidence.suggestionCount === 1 && !current.blocked };
        persistReport(report);
        return report;
    });
}

function parseArguments(args) {
    const config = { live: false, dryRun: false, preflight: false, keyConfirmed: false, syntheticConfirmed: false };
    const seen = new Set();
    for (let index = 0; index < args.length; index += 1) {
        const flag = args[index];
        if (seen.has(flag)) throw new Error("INVALID_ARGUMENTS");
        seen.add(flag);
        if (flag === "--live") config.live = true;
        else if (flag === "--dry-run") config.dryRun = true;
        else if (flag === "--preflight") config.preflight = true;
        else if (flag === "--confirm-helpdesk-key") config.keyConfirmed = true;
        else if (flag === "--confirm-synthetic") config.syntheticConfirmed = true;
        else if (flag === "--proceed-with-unknown-prior") config.proceedWithUnknownPrior = true;
        else if (flag === "--recover-launcher-once") config.recoverLauncherOnce = true;
        else if (["--day", "--budget-usd", "--prior-estimated-usd"].includes(flag)) {
            const value = args[++index];
            if (!value || value.startsWith("--")) throw new Error("INVALID_ARGUMENTS");
            if (flag === "--day") config.day = value;
            else if (flag === "--budget-usd") config.budgetUsd = Number(value);
            else config.knownPriorUsd = Number(value);
        } else throw new Error("INVALID_ARGUMENTS");
    }
    if ([config.live, config.dryRun, config.preflight].filter(Boolean).length !== 1) throw new Error("EXPLICIT_MODE_REQUIRED");
    return config;
}

async function main() {
    try {
        const config = parseArguments(process.argv.slice(2));
        if (config.dryRun) { console.log(safeReportJson(livePlan())); return; }
        if (config.preflight) { console.log(safeReportJson(runMavenPreflight())); return; }
        if (!config.keyConfirmed || !config.syntheticConfirmed || config.budgetUsd !== 1) {
            throw new Error("LIVE_APPROVAL_FLAGS_REQUIRED");
        }
        const today = new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Seoul",
            year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());
        if (config.day !== today) throw new Error("BUDGET_APPROVAL_DATE_MISMATCH");
        const apiKey = process.env.HELPDESK_OPENAI_API_KEY;
        const report = await executeLiveExperiment({ repositoryRoot: ROOT, day: config.day,
            knownPriorUsd: config.knownPriorUsd, proceedWithUnknownPrior: config.proceedWithUnknownPrior,
            recoverLauncherOnce: config.recoverLauncherOnce, apiKey,
            persistReport: value => writeFileSync(join(ROOT, "local", "ai-experiments",
                `${config.day}-java-provider-${Date.now()}.json`), safeReportJson(value, apiKey) + "\n", "utf8") });
        console.log(safeReportJson(report, apiKey));
        if (!report.completed) process.exitCode = 1;
    } catch (error) {
        const safeCodes = new Set(["HELPDESK_SCOPED_KEY_REQUIRED", "INVALID_ARGUMENTS", "EXPLICIT_MODE_REQUIRED",
            "LIVE_APPROVAL_FLAGS_REQUIRED", "BUDGET_APPROVAL_DATE_MISMATCH", "DAILY_LEDGER_INVALID",
            "KNOWN_PRIOR_COST_AND_VALID_BUDGET_REQUIRED", "PRIOR_COST_ONLY_ON_NEW_LEDGER", "BUDGET_RECONCILIATION_REQUIRED",
            "DAILY_CALL_OR_BUDGET_LIMIT_EXCEEDED", "DAILY_BUDGET_LOCK_UNAVAILABLE",
            "INVALID_PRIOR_COST_MODE", "UNCONFIRMED_PRIOR_SINGLE_CALL_APPROVAL_REQUIRED",
            "UNCONFIRMED_PRIOR_SINGLE_CALL_ALREADY_RESERVED",
            "MAVEN_STARTUP_PRECHECK_FAILED", "LAUNCHER_RECOVERY_NOT_APPLICABLE",
            "JAVA_OUTCOME_OR_USAGE_UNKNOWN_RECONCILE_BUDGET", "WINDOWS_EXPERIMENT_RUNNER_REQUIRED"]);
        console.error(safeCodes.has(error?.message) ? error.message : "JAVA_LIVE_EXPERIMENT_NOT_STARTED_OR_STOPPED");
        process.exitCode = 1;
    }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) await main();
