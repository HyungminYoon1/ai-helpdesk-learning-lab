import { existsSync, mkdirSync, openSync, closeSync, readFileSync, writeFileSync, renameSync, unlinkSync } from "node:fs";
import { resolve, join } from "node:path";

const MAX_DAILY_CALLS = 200;

function validDay(day) {
    return typeof day === "string" && /^\d{4}-\d{2}-\d{2}$/.test(day)
        && !Number.isNaN(Date.parse(day)) && new Date(day).toISOString().slice(0, 10) === day;
}

export function createDailyLedger(day, knownPriorUsd, limitUsd = 1) {
    if (!validDay(day) || !Number.isFinite(knownPriorUsd) || knownPriorUsd < 0
        || !Number.isFinite(limitUsd) || limitUsd <= 0 || limitUsd > 1 || knownPriorUsd > limitUsd) {
        throw new Error("KNOWN_PRIOR_COST_AND_VALID_BUDGET_REQUIRED");
    }
    return { version: 1, day, limitUsd, usedEstimatedUsd: knownPriorUsd, heldEstimatedUsd: 0,
        pendingReservationUsd: null, reservationsMade: 0, blocked: false, stopReason: null };
}

// Explicit one-call exception: track this runner, without inventing earlier spending.
export function createUnconfirmedPriorLedger(day, limitUsd = 1) {
    return { ...createDailyLedger(day, 0, limitUsd), priorCostUnconfirmed: true,
        priorEstimatedUsd: null, budgetScope: "TRACKED_RUNNER_CALLS_ONLY" };
}

function validateLedger(ledger, day, limitUsd) {
    const waived = ledger?.costLimitWaiver?.reason === "USER_APPROVED_NO_COST_LIMIT"
        && ledger.costLimitWaiver.day === day;
    if (!ledger || ledger.version !== 1 || ledger.day !== day || ledger.limitUsd !== limitUsd
        || ![ledger.usedEstimatedUsd, ledger.heldEstimatedUsd].every(value => Number.isFinite(value) && value >= 0)
        || !(ledger.pendingReservationUsd === null
            || Number.isFinite(ledger.pendingReservationUsd) && ledger.pendingReservationUsd > 0)
        || !Number.isSafeInteger(ledger.reservationsMade) || ledger.reservationsMade < 0
        || !waived && ledger.reservationsMade > MAX_DAILY_CALLS || typeof ledger.blocked !== "boolean"
        || ![null, "UNKNOWN_COST", "COST_ESTIMATE_EXCEEDED"].includes(ledger.stopReason)) {
        throw new Error("DAILY_LEDGER_INVALID");
    }
    if (ledger.costLimitWaiver !== undefined && !waived) throw new Error("DAILY_LEDGER_INVALID");
    const recovery = ledger.launcherRecovery;
    if (recovery !== undefined && (!recovery || recovery.reason !== "WINDOWS_MAVEN_LAUNCHER_REPAIR"
        || recovery.previousStopReason !== "UNKNOWN_COST" || recovery.previousReservationsMade !== 1
        || !Number.isFinite(recovery.heldReservationUsd) || recovery.heldReservationUsd <= 0
        || ledger.heldEstimatedUsd < recovery.heldReservationUsd || ledger.priorCostUnconfirmed !== true)) {
        throw new Error("DAILY_LEDGER_INVALID");
    }
    if (ledger.priorCostUnconfirmed !== undefined
        && (ledger.priorCostUnconfirmed !== true || ledger.priorEstimatedUsd !== null
            || ledger.budgetScope !== "TRACKED_RUNNER_CALLS_ONLY"
            || !waived && ledger.reservationsMade > (recovery ? 2 : 1))) {
        throw new Error("DAILY_LEDGER_INVALID");
    }
}

function authorizeLauncherRecovery(ledger, reservationUsd) {
    if (ledger.launcherRecovery !== undefined || ledger.priorCostUnconfirmed !== true
        || ledger.blocked !== true || ledger.stopReason !== "UNKNOWN_COST"
        || ledger.pendingReservationUsd !== null || ledger.reservationsMade !== 1
        || !Number.isFinite(reservationUsd) || reservationUsd <= 0
        || ledger.heldEstimatedUsd !== reservationUsd
        || ledger.usedEstimatedUsd + ledger.heldEstimatedUsd + reservationUsd > ledger.limitUsd) {
        throw new Error("LAUNCHER_RECOVERY_NOT_APPLICABLE");
    }
    // Keep the old unknown reservation held. This is not proof of zero earlier cost.
    return { ...ledger, blocked: false, stopReason: null, launcherRecovery: {
        reason: "WINDOWS_MAVEN_LAUNCHER_REPAIR", previousStopReason: "UNKNOWN_COST",
        previousReservationsMade: ledger.reservationsMade, heldReservationUsd: ledger.heldEstimatedUsd
    } };
}

export function reserveDailyCall(ledger, reservationUsd) {
    validateLedger(ledger, ledger.day, ledger.limitUsd);
    if (ledger.blocked || ledger.pendingReservationUsd !== null) throw new Error("BUDGET_RECONCILIATION_REQUIRED");
    const waived = ledger.costLimitWaiver !== undefined;
    if (!waived && ledger.priorCostUnconfirmed && ledger.reservationsMade >= (ledger.launcherRecovery ? 2 : 1)) {
        throw new Error("UNCONFIRMED_PRIOR_SINGLE_CALL_ALREADY_RESERVED");
    }
    if (!Number.isFinite(reservationUsd) || reservationUsd <= 0 || ledger.reservationsMade >= Number.MAX_SAFE_INTEGER
        || !waived && (ledger.reservationsMade >= MAX_DAILY_CALLS
            || ledger.usedEstimatedUsd + ledger.heldEstimatedUsd + reservationUsd > ledger.limitUsd)) {
        throw new Error("DAILY_CALL_OR_BUDGET_LIMIT_EXCEEDED");
    }
    return { ...ledger, pendingReservationUsd: reservationUsd, reservationsMade: ledger.reservationsMade + 1 };
}

export function settleDailyCall(ledger, estimatedUsageUsd) {
    validateLedger(ledger, ledger.day, ledger.limitUsd);
    if (ledger.pendingReservationUsd === null) throw new Error("NO_PENDING_RESERVATION");
    if (estimatedUsageUsd === null) {
        return { ...ledger, heldEstimatedUsd: ledger.heldEstimatedUsd + ledger.pendingReservationUsd,
            pendingReservationUsd: null, blocked: true, stopReason: "UNKNOWN_COST" };
    }
    if (!Number.isFinite(estimatedUsageUsd) || estimatedUsageUsd < 0) throw new Error("INVALID_USAGE_ESTIMATE");
    const usedEstimatedUsd = ledger.usedEstimatedUsd + estimatedUsageUsd;
    const exceeded = ledger.costLimitWaiver === undefined && (estimatedUsageUsd > ledger.pendingReservationUsd
        || usedEstimatedUsd + ledger.heldEstimatedUsd > ledger.limitUsd);
    return { ...ledger, usedEstimatedUsd, pendingReservationUsd: null, blocked: exceeded,
        stopReason: exceeded ? "COST_ESTIMATE_EXCEEDED" : null };
}

// Only this experimental runner's calls share the ledger. This is not an account-wide billing limit.
export async function withDailyLedger({ repositoryRoot, day, limitUsd = 1, knownPriorUsd,
    proceedWithUnknownPrior = false, recoverLauncherOnce = false, recoveryReservationUsd,
    confirmCostLimitWaiver = false }, callback) {
    if (!validDay(day) || !Number.isFinite(limitUsd) || limitUsd <= 0 || limitUsd > 1) {
        throw new Error("KNOWN_PRIOR_COST_AND_VALID_BUDGET_REQUIRED");
    }
    if (typeof proceedWithUnknownPrior !== "boolean"
        || typeof recoverLauncherOnce !== "boolean"
        || typeof confirmCostLimitWaiver !== "boolean"
        || proceedWithUnknownPrior && knownPriorUsd !== undefined
        || recoverLauncherOnce && !proceedWithUnknownPrior) {
        throw new Error("INVALID_PRIOR_COST_MODE");
    }
    const directory = resolve(repositoryRoot, "local", "ai-experiments");
    const file = join(directory, `${day}-budget.json`);
    const lockFile = `${file}.lock`;
    mkdirSync(directory, { recursive: true });
    let lock;
    try {
        lock = openSync(lockFile, "wx");
    } catch {
        throw new Error("DAILY_BUDGET_LOCK_UNAVAILABLE");
    }
    try {
        const present = existsSync(file);
        if (recoverLauncherOnce && !present) throw new Error("LAUNCHER_RECOVERY_NOT_APPLICABLE");
        if (present && knownPriorUsd !== undefined) throw new Error("PRIOR_COST_ONLY_ON_NEW_LEDGER");
        let ledger = present ? JSON.parse(readFileSync(file, "utf8"))
            : proceedWithUnknownPrior ? createUnconfirmedPriorLedger(day, limitUsd)
                : createDailyLedger(day, knownPriorUsd, limitUsd);
        validateLedger(ledger, day, limitUsd);
        const persist = value => {
            validateLedger(value, day, limitUsd);
            const temporaryFile = `${file}.tmp`;
            writeFileSync(temporaryFile, JSON.stringify(value, null, 2) + "\n", "utf8");
            renameSync(temporaryFile, file);
            ledger = value;
        };
        if (recoverLauncherOnce) {
            ledger = authorizeLauncherRecovery(ledger, recoveryReservationUsd);
            persist(ledger);
        }
        if (ledger.costLimitWaiver !== undefined && !confirmCostLimitWaiver) {
            throw new Error("COST_LIMIT_WAIVER_CONFIRMATION_REQUIRED");
        }
        if (confirmCostLimitWaiver) {
            // A new, explicit daily approval removes spending caps, not the usage history.
            // An unfinished reservation still needs reconciliation; it is not erased.
            if (ledger.pendingReservationUsd !== null) throw new Error("BUDGET_RECONCILIATION_REQUIRED");
            const previousStopReason = ledger.costLimitWaiver?.previousStopReason ?? ledger.stopReason;
            persist({ ...ledger, blocked: false, stopReason: null,
                costLimitWaiver: { reason: "USER_APPROVED_NO_COST_LIMIT", day, previousStopReason } });
        }
        if (ledger.blocked || ledger.pendingReservationUsd !== null) throw new Error("BUDGET_RECONCILIATION_REQUIRED");
        if (ledger.priorCostUnconfirmed && !proceedWithUnknownPrior) {
            throw new Error("UNCONFIRMED_PRIOR_SINGLE_CALL_APPROVAL_REQUIRED");
        }
        if (!present) persist(ledger);
        return await callback({ ledger, persist });
    } finally {
        closeSync(lock);
        unlinkSync(lockFile);
    }
}
