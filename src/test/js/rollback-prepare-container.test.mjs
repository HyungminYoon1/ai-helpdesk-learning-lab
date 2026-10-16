import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { URL } from "node:url";

const verifier = readFileSync(new URL("../../../scripts/Verify-Week8Compose.ps1", import.meta.url), "utf8");
const compatibility = readFileSync(new URL("../../../scripts/rollback/Verify-PrepareCompatibility.ps1", import.meta.url), "utf8");
const override = readFileSync(new URL("../../../compose.rollback-fixture.yaml", import.meta.url), "utf8");

// Source safeguards only. The PowerShell verifier produces the live-container evidence.
test("prepare Image compatibility is opt-in and requires the isolated deployment profile", () => {
    assert.ok(verifier.includes("[switch]$RollbackPrepareCompatibility"));
    assert.ok(verifier.includes("'PREPARE_REQUIRES_ISOLATED_DEPLOYMENT_PROBE'"));
    assert.ok(verifier.includes("if ($RollbackPrepareCompatibility) {"));
});

test("V8 is an external read-only test mount, not a P Image migration", () => {
    assert.ok(override.includes("classpath:db/migration,filesystem:/rollback-test-migrations"));
    assert.ok(override.includes("./src/test/resources/rollback-compatibility:/rollback-test-migrations:ro"));
    assert.equal(/\bimage:|\bbuild:|\bports:|PASSWORD|API_KEY/.test(override), false);
    assert.ok(compatibility.includes("'PREPARE_V8_FIXTURE_CHANGED'"));
    assert.ok(compatibility.includes("'PREPARE_NORMAL_IMAGE_NOT_RECREATED'"));
});

test("normal P startup preserves migration history and row hashes without disabling validation", () => {
    for (const marker of ["PREPARE_V7_ACCEPTED_NEW_FAILURE", "PREPARE_V8_REWROTE_EXISTING_DATA",
        "PREPARE_STARTUP_REWROTE_MIGRATION_HISTORY", "PREPARE_V8_READ_CHANGED_ROWS"]) {
        assert.ok(compatibility.includes(marker));
    }
    assert.equal(/SPRING_FLYWAY_ENABLED|IGNORE_MIGRATION|VALIDATE_ON_MIGRATE/.test(override + compatibility), false);
});

test("recreated JVM loses the session but retains the same database and new failure meanings", () => {
    for (const marker of ["PREPARE_OLD_JVM_SESSION_SURVIVED", "PREPARE_DATABASE_PROCESS_CHANGED",
        "PREPARE_V8_SECURITY_CHANGED", "PRIVACY_SCAN_TIMEOUT", "PRIVACY_SCAN_INVALID_RESULT"]) {
        assert.ok(compatibility.includes(marker));
    }
    assert.ok(compatibility.includes("providerResultsAreSqlFixtures = $true; liveAiCalls = 0"));
    assert.ok(compatibility.includes("browserE2e = $false; awsAccessed = $false"));
});
