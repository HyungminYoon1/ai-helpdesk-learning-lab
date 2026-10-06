import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { executeWorkerBrowser, readWorkerBrowserEvidence, workerBrowserCompleted, workerBrowserPlan }
    from "../../../scripts/week7-worker-browser-live.mjs";
import { withDailyLedger, reserveDailyCall, settleDailyCall } from "../../../scripts/week7-ai-daily-budget.mjs";
import { RESERVATION_USD } from "../../../scripts/week7-java-provider-live.mjs";

const DAY = "2026-10-06";
const root = () => mkdtempSync(join(tmpdir(), "week7-worker-browser-test-"));
const ledgerFile = repositoryRoot => join(repositoryRoot, "local", "ai-experiments", `${DAY}-budget.json`);
const preflight = () => ({ passed: true, credentialsPassed: false });
const fixture = extra => ({ evidence: "LIVE_WORKER_BROWSER_POSTGRES", protocol: "chat-completions",
    manualContentReview: "NOT_SCORED", jobStatus: "SUCCEEDED", browserE2e: true, workerScheduled: true,
    originalPreserved: true, authorFromAuthentication: true, databaseAndUiAgree: true, readOnlyQueryPreserved: true,
    sessionCookieObserved: true, csrfHeaderObserved: true, receiptStatus: 201, missingCsrfStatus: 403,
    anonymousQueryStatus: 401, userQueryStatus: 403, agentQueryStatus: 200, ticketId: 1,
    providerInvocations: 1, reservedGenerationCount: 1, suggestionCount: 1, categoryCount: 1,
    ticketStatus: "OPEN", reviewStatus: "PENDING_REVIEW", httpAttempts: 1, httpStatus: 200,
    expectedTariffConfirmed: true, usage: { inputTokens: 1000, outputTokens: 100 }, javaExitSucceeded: true,
    screenshot: "output/playwright/week7-worker-123/agent-suggestion.png", ...extra });
const evidenceText = raw => "HELPDESK_WORKER_BROWSER_EVIDENCE " + JSON.stringify(raw);
const options = extra => ({ repositoryRoot: root(), day: DAY, apiKey: "synthetic-test-only-key",
    confirmCostLimitWaiver: true, runPreflight: preflight, runJava: () => fixture(), ...extra });

test("dry run describes one inquiry without a key or paid call", () => {
    const plan = workerBrowserPlan();
    assert.equal(plan.callsAttempted, 0);
    assert.equal(plan.callsPlanned, 1);
    assert.equal(plan.globalJobPolicyChanged, false);
});

test("safe evidence drops arbitrary diagnostics and non-fixture screenshot paths", () => {
    const raw = fixture({ apiKey: "PRIVATE", headers: "PRIVATE", screenshot: "C:/PRIVATE.png" });
    const report = readWorkerBrowserEvidence(evidenceText(raw), true);
    assert.equal(JSON.stringify(report).includes("PRIVATE"), false);
    assert.equal("screenshot" in report, false);
    assert.equal(report.usage.inputTokens, 1000);
});

test("missing or duplicate evidence and extra HTTP attempts cannot prove a completed experiment", () => {
    for (const value of ["", evidenceText(fixture()) + "\n" + evidenceText(fixture()),
        evidenceText(fixture({ httpAttempts: 2 })), evidenceText(fixture({ jobStatus: "UNKNOWN" })),
        evidenceText(fixture({ manualContentReview: "PASSED" }))]) {
        assert.throws(() => readWorkerBrowserEvidence(value, true), /EVIDENCE_INVALID/);
    }
});

test("controlled provider evidence is explicitly separate from a real AI call", () => {
    const raw = fixture({ evidence: "CONTROLLED_WORKER_BROWSER_POSTGRES", protocol: "test-double", httpAttempts: 0 });
    const report = readWorkerBrowserEvidence(evidenceText(raw), false);
    assert.equal(report.protocol, "test-double");
    assert.throws(() => readWorkerBrowserEvidence(evidenceText(raw), true), /EVIDENCE_INVALID/);
});

test("HTTP 201 alone and a successful Java exit do not establish the end-to-end flow", () => {
    for (const extra of [{ browserE2e: false }, { databaseAndUiAgree: false }, { originalPreserved: false },
        { readOnlyQueryPreserved: false }, { userQueryStatus: 200 }, { csrfHeaderObserved: false },
        { providerInvocations: 0 }, { suggestionCount: 0 }, { reviewStatus: "APPROVED" }, { ticketStatus: "RESOLVED" }]) {
        assert.equal(workerBrowserCompleted(fixture(extra), true), false);
    }
    assert.equal(workerBrowserCompleted(fixture(), true), true);
});

test("an explicit daily waiver records extra reservation without resetting unknown history", async () => {
    const repositoryRoot = root();
    await withDailyLedger({ repositoryRoot, day: DAY, proceedWithUnknownPrior: true }, ({ ledger, persist }) => {
        persist(settleDailyCall(reserveDailyCall(ledger, RESERVATION_USD), 0.000173125));
    });
    const report = await executeWorkerBrowser(options({ repositoryRoot, runJava: () => {
        const ledger = JSON.parse(readFileSync(ledgerFile(repositoryRoot), "utf8"));
        assert.equal(ledger.reservationsMade, 2);
        assert.equal(ledger.usedEstimatedUsd, 0.000173125);
        assert.equal(ledger.pendingReservationUsd, RESERVATION_USD);
        assert.equal(ledger.costLimitWaiver.reason, "USER_APPROVED_NO_COST_LIMIT");
        return fixture();
    } }));
    assert.equal(report.completed, true);
    assert.equal(report.costLimitEnforced, false);
    assert.equal(report.dailyEstimatedTotalUsd, null);
    assert.equal(report.dailyEstimateComplete, false);
    assert.equal(report.dailyLedger.priorEstimatedUsd, null);
});

test("waiver retains unknown held reservations rather than treating them as free", async () => {
    const repositoryRoot = root();
    await withDailyLedger({ repositoryRoot, day: DAY, proceedWithUnknownPrior: true }, ({ ledger, persist }) => {
        persist(settleDailyCall(reserveDailyCall(ledger, RESERVATION_USD), null));
    });
    const report = await executeWorkerBrowser(options({ repositoryRoot }));
    assert.equal(report.dailyLedger.heldEstimatedUsd, RESERVATION_USD);
    assert.equal(report.dailyLedger.costLimitWaiver.previousStopReason, "UNKNOWN_COST");
    assert.equal(report.dailyLedger.reservationsMade, 2);
});

test("a stored waiver cannot silently authorize an unrelated runner", async () => {
    const repositoryRoot = root();
    await executeWorkerBrowser(options({ repositoryRoot }));
    const before = readFileSync(ledgerFile(repositoryRoot), "utf8");
    let starts = 0;
    await assert.rejects(withDailyLedger({ repositoryRoot, day: DAY, proceedWithUnknownPrior: true },
        () => { starts += 1; }), /WAIVER_CONFIRMATION_REQUIRED/);
    assert.equal(starts, 0);
    assert.equal(readFileSync(ledgerFile(repositoryRoot), "utf8"), before);
});

test("a cost waiver never clears an unfinished reservation", async () => {
    const repositoryRoot = root();
    await withDailyLedger({ repositoryRoot, day: DAY, proceedWithUnknownPrior: true }, ({ ledger, persist }) => {
        persist(reserveDailyCall(ledger, RESERVATION_USD));
    });
    const before = readFileSync(ledgerFile(repositoryRoot), "utf8");
    let starts = 0;
    await assert.rejects(executeWorkerBrowser(options({ repositoryRoot, runJava: () => { starts += 1; return fixture(); } })),
        /RECONCILIATION_REQUIRED/);
    assert.equal(starts, 0);
    assert.equal(readFileSync(ledgerFile(repositoryRoot), "utf8"), before);
});

test("no key, no explicit approval, or failed preflight prevents even a reservation", async () => {
    for (const extra of [{ apiKey: undefined }, { confirmCostLimitWaiver: false },
        { runPreflight: () => ({ passed: false, credentialsPassed: false }) }]) {
        const repositoryRoot = root();
        let starts = 0;
        await assert.rejects(executeWorkerBrowser(options({ ...extra, repositoryRoot,
            runJava: () => { starts += 1; return fixture(); } })));
        assert.equal(starts, 0);
        assert.equal(existsSync(ledgerFile(repositoryRoot)), false);
    }
});

test("unknown subprocess outcome is recorded and not automatically relaunched", async () => {
    const repositoryRoot = root();
    let starts = 0;
    await assert.rejects(executeWorkerBrowser(options({ repositoryRoot, runJava: () => {
        starts += 1; throw new Error("PRIVATE");
    } })), /OUTCOME_UNKNOWN/);
    const ledger = JSON.parse(readFileSync(ledgerFile(repositoryRoot), "utf8"));
    assert.equal(starts, 1);
    assert.equal(ledger.heldEstimatedUsd, RESERVATION_USD);
    assert.equal(ledger.blocked, true);
});

test("the explicit waiver removes the spending cap but keeps usage estimates", async () => {
    const repositoryRoot = root();
    await withDailyLedger({ repositoryRoot, day: DAY, knownPriorUsd: 1 }, () => {});
    const report = await executeWorkerBrowser(options({ repositoryRoot }));
    assert.equal(report.completed, true);
    assert.equal(report.dailyEstimatedTotalUsd, 1.000175);
    assert.equal(report.dailyLedger.limitUsd, 1); // Historical approved cap, no longer enforced.
    assert.equal(report.dailyLedger.blocked, false);
});
