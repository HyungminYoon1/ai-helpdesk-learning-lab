import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { livePlan, readSafeJavaEvidence, executeLiveExperiment, RESERVATION_USD } from "../../../scripts/week7-java-provider-live.mjs";

const DAY = "2026-10-06";
const PREFIX = "HELPDESK_JAVA_LIVE_EVIDENCE ";
const fixture = extra => ({ evidence: "LIVE_JAVA_SPRING_AI_POSTGRES", caseId: "SYNTHETIC_LOGIN_RECOVERY",
    protocol: "chat-completions", manualContentReview: "NOT_SCORED", browserE2e: false,
    outcome: "STORED", httpAttempts: 1, httpStatus: 200, usage: { inputTokens: 1000, outputTokens: 100 },
    expectedTariffConfirmed: true, reservedGenerationCount: 1, originalPreserved: true,
    jobSucceeded: true, suggestionCount: 1, categoryCount: 1, javaExitSucceeded: true, ...extra });
const root = () => mkdtempSync(join(tmpdir(), "week7-java-live-test-"));
const ledgerPath = repositoryRoot => join(repositoryRoot, "local", "ai-experiments", `${DAY}-budget.json`);

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
