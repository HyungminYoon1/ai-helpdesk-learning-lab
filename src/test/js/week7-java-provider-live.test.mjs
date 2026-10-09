import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { livePlan, readSafeJavaEvidence, executeLiveExperiment as runLiveExperiment, RESERVATION_USD,
    scopedChildEnvironment, runMavenPreflight, windowsShell } from "../../../scripts/week7-java-provider-live.mjs";
import { withDailyLedger, reserveDailyCall } from "../../../scripts/week7-ai-daily-budget.mjs";

const DAY = "2026-10-06";
const PREFIX = "HELPDESK_JAVA_LIVE_EVIDENCE ";
const fixture = extra => ({ evidence: "LIVE_JAVA_SPRING_AI_POSTGRES", caseId: "SYNTHETIC_LOGIN_RECOVERY",
    protocol: "chat-completions", manualContentReview: "NOT_SCORED", browserE2e: false,
    outcome: "STORED", httpAttempts: 1, httpStatus: 200, usage: { inputTokens: 1000, outputTokens: 100 },
    expectedTariffConfirmed: true, reservedGenerationCount: 1, originalPreserved: true,
    jobSucceeded: true, suggestionCount: 1, categoryCount: 1, javaExitSucceeded: true, ...extra });
const root = () => mkdtempSync(join(tmpdir(), "week7-java-live-test-"));
const ledgerPath = repositoryRoot => join(repositoryRoot, "local", "ai-experiments", `${DAY}-budget.json`);
const preflightEvidence = () => ({ evidence: "MAVEN_PREFLIGHT_NO_API_CALL", passed: true, credentialsPassed: false });
const executeLiveExperiment = options => runLiveExperiment({ runPreflight: preflightEvidence, ...options });

test("dry run plans one isolated experiment and does not need a key or call a transport", () => {
    const plan = livePlan();
    assert.equal(plan.callsAttempted, 0);
    assert.equal(plan.callsPlanned, 1);
    assert.equal(plan.protocol, "chat-completions");
    assert.equal(plan.reservedCostUsd, 0.009004);
});

test("only safe metadata is forwarded from Java output, not raw prompt or credentials", () => {
    const evidence = readSafeJavaEvidence("untrusted diagnostics\n" + PREFIX
        + JSON.stringify(fixture({ rawPrompt: "PRIVATE", apiKey: "PRIVATE", responseBody: "PRIVATE" })) + "\n");
    assert.equal(JSON.stringify(evidence).includes("PRIVATE"), false);
    assert.equal(evidence.httpAttempts, 1);
    assert.equal(evidence.suggestionCount, 1);
});

test("missing, duplicate, malformed or unexpected evidence is not zero-cost proof", () => {
    for (const value of ["", PREFIX + "bad json", PREFIX + JSON.stringify(fixture({ httpAttempts: 2 })),
        PREFIX + JSON.stringify(fixture({ outcome: "UNTRUSTED" })),
        PREFIX + JSON.stringify(fixture()) + "\n" + PREFIX + JSON.stringify(fixture())]) {
        assert.throws(() => readSafeJavaEvidence(value), /EVIDENCE_MISSING_OR_INVALID/);
    }
});

test("budget reservation is committed before the scoped Java call and includes earlier spending", async () => {
    const repositoryRoot = root();
    const report = await executeLiveExperiment({ repositoryRoot, day: DAY, knownPriorUsd: 0.8,
        apiKey: "synthetic-test-only-credential", runJava: ({ apiKey, day }) => {
            assert.equal(apiKey, "synthetic-test-only-credential");
            assert.equal(day, DAY);
            const ledger = JSON.parse(readFileSync(ledgerPath(repositoryRoot), "utf8"));
            assert.equal(ledger.pendingReservationUsd, RESERVATION_USD);
            assert.equal(ledger.usedEstimatedUsd, 0.8);
            return fixture();
        } });
    assert.equal(report.completed, true);
    assert.equal(report.estimatedCostUsd, 0.000175);
    assert.equal(report.dailyLedger.reservationsMade, 1);
});

test("missing usage blocks the next paid experiment without resetting the daily ledger", async () => {
    const repositoryRoot = root();
    const args = { repositoryRoot, day: DAY, apiKey: "synthetic-test-only-credential",
        runJava: () => fixture({ usage: null }) };
    const report = await executeLiveExperiment({ ...args, knownPriorUsd: 0 });
    assert.equal(report.completed, false);
    assert.equal(report.dailyLedger.blocked, true);
    assert.equal(report.dailyLedger.heldEstimatedUsd, RESERVATION_USD);
    await assert.rejects(executeLiveExperiment(args), /RECONCILIATION/);
});

test("an unrecognized returned tariff is not priced as the expected model", async () => {
    const report = await executeLiveExperiment({ repositoryRoot: root(), day: DAY, knownPriorUsd: 0,
        apiKey: "synthetic-test-only-credential", runJava: () => fixture({ expectedTariffConfirmed: false }) });
    assert.equal(report.estimatedCostUsd, null);
    assert.equal(report.dailyLedger.blocked, true);
});

test("unknown Java outcome retains its reservation instead of relaunching Java", async () => {
    const repositoryRoot = root();
    let launches = 0;
    await assert.rejects(executeLiveExperiment({ repositoryRoot, day: DAY, knownPriorUsd: 0,
        apiKey: "synthetic-test-only-credential", runJava: () => { launches += 1; throw new Error("PRIVATE"); } }),
    /OUTCOME_OR_USAGE_UNKNOWN/);
    assert.equal(launches, 1);
    assert.equal(JSON.parse(readFileSync(ledgerPath(repositoryRoot), "utf8")).blocked, true);
});

test("missing explicit key and insufficient remaining budget prevent Java execution", async () => {
    let launches = 0;
    const args = { repositoryRoot: root(), day: DAY, runJava: () => { launches += 1; return fixture(); } };
    await assert.rejects(executeLiveExperiment({ ...args, knownPriorUsd: 0 }), /SCOPED_KEY_REQUIRED/);
    await assert.rejects(executeLiveExperiment({ ...args, apiKey: "synthetic-test-only-credential", knownPriorUsd: 0.999 }),
        /BUDGET_LIMIT/);
    assert.equal(launches, 0);
});

test("no database row or failed Java process is presented as a completed live flow", async () => {
    for (const extra of [{ suggestionCount: 0 }, { javaExitSucceeded: false }, { originalPreserved: false }]) {
        const report = await executeLiveExperiment({ repositoryRoot: root(), day: DAY, knownPriorUsd: 0,
            apiKey: "synthetic-test-only-credential", runJava: () => fixture(extra) });
        assert.equal(report.completed, false);
    }
});

test("explicit continuation keeps earlier cost unknown while reserving one tracked call", async () => {
    const repositoryRoot = root();
    const report = await executeLiveExperiment({ repositoryRoot, day: DAY, proceedWithUnknownPrior: true,
        apiKey: "synthetic-test-only-credential", runJava: ({ priorCostUnconfirmed }) => {
            assert.equal(priorCostUnconfirmed, true);
            const ledger = JSON.parse(readFileSync(ledgerPath(repositoryRoot), "utf8"));
            assert.equal(ledger.priorEstimatedUsd, null);
            assert.equal(ledger.priorCostUnconfirmed, true);
            assert.equal(ledger.budgetScope, "TRACKED_RUNNER_CALLS_ONLY");
            assert.equal(ledger.pendingReservationUsd, RESERVATION_USD);
            assert.equal(ledger.reservationsMade, 1);
            return fixture();
        } });
    assert.equal(report.completed, true);
    assert.equal(report.priorEstimatedUsd, null);
    assert.equal(report.dailyEstimatedTotalUsd, null);
    assert.equal(report.dailyEstimateComplete, false);
    assert.equal(report.dailyLedger.usedEstimatedUsd, report.estimatedCostUsd);
});

test("unknown earlier cost is not silently accepted without the continuation flag", async () => {
    let launches = 0;
    await assert.rejects(executeLiveExperiment({ repositoryRoot: root(), day: DAY,
        apiKey: "synthetic-test-only-credential", runJava: () => { launches += 1; return fixture(); } }),
    /KNOWN_PRIOR_COST/);
    assert.equal(launches, 0);
});

test("unconfirmed earlier cost does not permit another call or reset the ledger", async () => {
    const repositoryRoot = root();
    let launches = 0;
    const args = { repositoryRoot, day: DAY, proceedWithUnknownPrior: true,
        apiKey: "synthetic-test-only-credential", runJava: () => { launches += 1; return fixture(); } };
    await executeLiveExperiment(args);
    const before = readFileSync(ledgerPath(repositoryRoot), "utf8");
    await assert.rejects(executeLiveExperiment(args), /SINGLE_CALL_ALREADY_RESERVED/);
    assert.equal(launches, 1);
    assert.equal(readFileSync(ledgerPath(repositoryRoot), "utf8"), before);
});

test("other runners cannot adopt the unconfirmed-prior exception implicitly", async () => {
    const repositoryRoot = root();
    await executeLiveExperiment({ repositoryRoot, day: DAY, proceedWithUnknownPrior: true,
        apiKey: "synthetic-test-only-credential", runJava: () => fixture() });
    let callbacks = 0;
    await assert.rejects(withDailyLedger({ repositoryRoot, day: DAY }, () => { callbacks += 1; }),
        /SINGLE_CALL_APPROVAL_REQUIRED/);
    assert.equal(callbacks, 0);
});

test("continuation never replaces confirmed earlier spending or an existing ledger", async () => {
    const repositoryRoot = root();
    await withDailyLedger({ repositoryRoot, day: DAY, knownPriorUsd: 0.5 }, () => {});
    const report = await executeLiveExperiment({ repositoryRoot, day: DAY, proceedWithUnknownPrior: true,
        apiKey: "synthetic-test-only-credential", runJava: ({ priorCostUnconfirmed }) => {
            assert.equal(priorCostUnconfirmed, false);
            return fixture();
        } });
    assert.equal(report.priorEstimatedUsd, 0.5);
    assert.equal(report.dailyEstimateComplete, true);
    assert.equal(report.dailyEstimatedTotalUsd, 0.500175);
});

test("confirmed and unconfirmed prior modes cannot be combined", async () => {
    let launches = 0;
    await assert.rejects(executeLiveExperiment({ repositoryRoot: root(), day: DAY, knownPriorUsd: 0,
        proceedWithUnknownPrior: true, apiKey: "synthetic-test-only-credential",
        runJava: () => { launches += 1; return fixture(); } }), /INVALID_PRIOR_COST_MODE/);
    assert.equal(launches, 0);
});

test("an unconfirmed-prior call with unknown outcome is still held without automatic retry", async () => {
    const repositoryRoot = root();
    let launches = 0;
    const args = { repositoryRoot, day: DAY, proceedWithUnknownPrior: true,
        apiKey: "synthetic-test-only-credential", runJava: () => { launches += 1; throw new Error("PRIVATE"); } };
    await assert.rejects(executeLiveExperiment(args), /OUTCOME_OR_USAGE_UNKNOWN/);
    const ledger = JSON.parse(readFileSync(ledgerPath(repositoryRoot), "utf8"));
    assert.equal(ledger.blocked, true);
    assert.equal(ledger.heldEstimatedUsd, RESERVATION_USD);
    assert.equal(ledger.priorEstimatedUsd, null);
    await assert.rejects(executeLiveExperiment(args), /RECONCILIATION/);
    assert.equal(launches, 1);
});

test("Windows wrapper variables are forwarded without unrelated keys or Maven options", () => {
    const host = { PATH: "safe-path", PATHEXT: ".EXE;.BAT;.CMD", COMSPEC: "safe-command-interpreter",
        OPENAI_API_KEY: "unrelated-private-value", HELPDESK_OPENAI_API_KEY: "inherited-private-value",
        MVNW_PASSWORD: "unrelated-private-value", MAVEN_OPTS: "unrelated-private-value" };
    const scoped = scopedChildEnvironment({ apiKey: "synthetic-scoped-credential", day: DAY,
        priorCostUnconfirmed: true, launcherRecoveryConfirmed: false }, host);
    assert.equal(scoped.PATHEXT, host.PATHEXT);
    assert.equal(scoped.COMSPEC, host.COMSPEC);
    assert.equal(scoped.HELPDESK_OPENAI_API_KEY, "synthetic-scoped-credential");
    for (const name of ["OPENAI_API_KEY", "MVNW_PASSWORD", "MAVEN_OPTS"]) assert.equal(name in scoped, false);
    const preflight = scopedChildEnvironment({}, host);
    assert.equal("HELPDESK_OPENAI_API_KEY" in preflight, false);
    assert.equal("HELPDESK_AI_LIVE_CONFIRMED" in preflight, false);
});

test("Maven preflight checks real version output and sends no credentials", () => {
    const evidence = runMavenPreflight({ hostEnvironment: { PATHEXT: ".CMD", COMSPEC: "safe-interpreter",
        OPENAI_API_KEY: "PRIVATE", HELPDESK_OPENAI_API_KEY: "PRIVATE", SYSTEMROOT: "C:\\Windows" },
    resolveShell: () => "synthetic-windows-shell",
    spawnProcess: (shell, args, options) => {
        assert.equal(shell, "synthetic-windows-shell");
        assert.equal(args.at(-1), ".\\mvnw.cmd --version; exit $LASTEXITCODE");
        assert.equal("OPENAI_API_KEY" in options.env, false);
        assert.equal("HELPDESK_OPENAI_API_KEY" in options.env, false);
        assert.equal("HELPDESK_AI_LIVE_CONFIRMED" in options.env, false);
        return { status: 0, stdout: "Apache Maven 3.9.16\nJava version: 25.0.4\n" };
    } });
    assert.deepEqual(evidence, preflightEvidence());
});

test("zero exit with no Maven output and startup diagnostics are not successful preflight", () => {
    for (const child of [{ status: 0, stdout: "" }, { status: 1, stderr: "PRIVATE" },
        { status: 0, stdout: "Apache Maven 3.9.16\nJava version: 17.0.1" },
        { status: 0, stdout: "Apache Maven 3.8.8\nJava version: 25.0.4" },
        { error: new Error("PRIVATE") }]) {
        assert.throws(() => runMavenPreflight({ hostEnvironment: { PATHEXT: ".CMD", COMSPEC: "safe-interpreter" },
            resolveShell: () => "synthetic-windows-shell",
            spawnProcess: () => child }), error => error.message === "MAVEN_STARTUP_PRECHECK_FAILED"
                && error.cause === undefined);
    }
});

test("the default preflight keeps its Windows-only guard before starting a subprocess", () => {
    const hostEnvironment = { PATHEXT: ".CMD", COMSPEC: "safe-interpreter", SYSTEMROOT: "C:\\Windows" };
    if (process.platform === "win32") {
        assert.equal(typeof windowsShell(hostEnvironment), "string");
        return;
    }
    let starts = 0;
    assert.throws(() => runMavenPreflight({ hostEnvironment,
        spawnProcess: () => { starts += 1; return { status: 0 }; } }),
    /WINDOWS_EXPERIMENT_RUNNER_REQUIRED/);
    assert.equal(starts, 0);
});

test("missing Windows wrapper variables prevent even the preflight subprocess", () => {
    let starts = 0;
    assert.throws(() => runMavenPreflight({ hostEnvironment: {},
        spawnProcess: () => { starts += 1; return { status: 0 }; } }), /STARTUP_PRECHECK/);
    assert.equal(starts, 0);
});

test("preflight failure occurs before a ledger or a paid-call reservation is created", async () => {
    const repositoryRoot = root();
    let starts = 0;
    await assert.rejects(executeLiveExperiment({ repositoryRoot, day: DAY, proceedWithUnknownPrior: true,
        apiKey: "synthetic-test-only-credential", runPreflight: () => ({ passed: false }),
        runJava: () => { starts += 1; return fixture(); } }), /STARTUP_PRECHECK/);
    assert.equal(starts, 0);
    assert.equal(existsSync(ledgerPath(repositoryRoot)), false);
});

test("manual launcher recovery retains the old unknown reservation and counts the new one", async () => {
    const repositoryRoot = root();
    const args = { repositoryRoot, day: DAY, proceedWithUnknownPrior: true,
        apiKey: "synthetic-test-only-credential" };
    await assert.rejects(executeLiveExperiment({ ...args, runJava: () => { throw new Error("PRIVATE"); } }),
        /OUTCOME_OR_USAGE_UNKNOWN/);
    const report = await executeLiveExperiment({ ...args, recoverLauncherOnce: true,
        runJava: ({ launcherRecoveryConfirmed }) => {
            assert.equal(launcherRecoveryConfirmed, true);
            const ledger = JSON.parse(readFileSync(ledgerPath(repositoryRoot), "utf8"));
            assert.equal(ledger.reservationsMade, 2);
            assert.equal(ledger.heldEstimatedUsd, RESERVATION_USD);
            assert.equal(ledger.pendingReservationUsd, RESERVATION_USD);
            assert.equal(ledger.launcherRecovery.previousStopReason, "UNKNOWN_COST");
            return fixture();
        } });
    assert.equal(report.completed, true);
    assert.equal(report.dailyLedger.heldEstimatedUsd, RESERVATION_USD);
    assert.equal(report.priorEstimatedUsd, null);
    assert.equal(report.dailyEstimatedTotalUsd, null);
    await assert.rejects(executeLiveExperiment({ ...args, runJava: () => fixture() }), /SINGLE_CALL_ALREADY_RESERVED/);
});

test("failed preflight never releases an existing block or changes its history", async () => {
    const repositoryRoot = root();
    const args = { repositoryRoot, day: DAY, proceedWithUnknownPrior: true,
        apiKey: "synthetic-test-only-credential" };
    await assert.rejects(executeLiveExperiment({ ...args, runJava: () => { throw new Error("PRIVATE"); } }),
        /OUTCOME_OR_USAGE_UNKNOWN/);
    const before = readFileSync(ledgerPath(repositoryRoot), "utf8");
    await assert.rejects(executeLiveExperiment({ ...args, recoverLauncherOnce: true,
        runPreflight: () => ({ passed: false }), runJava: () => fixture() }), /STARTUP_PRECHECK/);
    assert.equal(readFileSync(ledgerPath(repositoryRoot), "utf8"), before);
});

test("launcher recovery is not a way to create a fresh ledger or retry an active reservation", async () => {
    const repositoryRoot = root();
    const args = { repositoryRoot, day: DAY, proceedWithUnknownPrior: true, recoverLauncherOnce: true,
        apiKey: "synthetic-test-only-credential", runJava: () => fixture() };
    await assert.rejects(executeLiveExperiment(args), /RECOVERY_NOT_APPLICABLE/);
    assert.equal(existsSync(ledgerPath(repositoryRoot)), false);
    await withDailyLedger({ repositoryRoot, day: DAY, proceedWithUnknownPrior: true }, ({ ledger, persist }) => {
        persist(reserveDailyCall(ledger, RESERVATION_USD));
    });
    const before = readFileSync(ledgerPath(repositoryRoot), "utf8");
    await assert.rejects(executeLiveExperiment(args), /RECOVERY_NOT_APPLICABLE/);
    assert.equal(readFileSync(ledgerPath(repositoryRoot), "utf8"), before);
});

test("manual launcher recovery cannot be repeated after another unknown outcome", async () => {
    const repositoryRoot = root();
    const args = { repositoryRoot, day: DAY, proceedWithUnknownPrior: true,
        apiKey: "synthetic-test-only-credential", runJava: () => { throw new Error("PRIVATE"); } };
    await assert.rejects(executeLiveExperiment(args), /OUTCOME_OR_USAGE_UNKNOWN/);
    await assert.rejects(executeLiveExperiment({ ...args, recoverLauncherOnce: true }), /OUTCOME_OR_USAGE_UNKNOWN/);
    const before = readFileSync(ledgerPath(repositoryRoot), "utf8");
    const ledger = JSON.parse(before);
    assert.equal(ledger.heldEstimatedUsd, 2 * RESERVATION_USD);
    assert.equal(ledger.reservationsMade, 2);
    await assert.rejects(executeLiveExperiment({ ...args, recoverLauncherOnce: true }), /RECOVERY_NOT_APPLICABLE/);
    assert.equal(readFileSync(ledgerPath(repositoryRoot), "utf8"), before);
});
