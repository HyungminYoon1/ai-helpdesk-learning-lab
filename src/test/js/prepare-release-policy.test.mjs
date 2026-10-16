import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { URL } from "node:url";
import { PREPARE_PATHS, assertPrepareDiff, validatePrepareManifest } from "../../../scripts/verify-prepare-release.mjs";

const manifest = JSON.parse(readFileSync(new URL("../../../deploy/rollback/prepare-release.json", import.meta.url), "utf8"));
const workflow = readFileSync(new URL("../../../.github/workflows/verify-prepare.yml", import.meta.url), "utf8");
const fixturePath = "src/test/resources/rollback-compatibility/V8__allow_privacy_scan_failures.sql";
const hashes = { ...manifest.productionSha256, [fixturePath]: manifest.v8FixtureSha256 };
const copy = () => JSON.parse(JSON.stringify(manifest));

test("release policy pins the agreed repository, branch, base and full P commit", () => {
    assert.equal(validatePrepareManifest(manifest), manifest);
    for (const patch of [{ repository: "someone/other" }, { branch: "main" }, { imageRole: "active" },
        { sourceCommit: "main" }, { sourceCommit: "$(arbitrary)" }, { baseCommit: "0".repeat(40) }]) {
        assert.throws(() => validatePrepareManifest({ ...manifest, ...patch }));
    }
});

test("only the two reviewed production files can have expected hashes", () => {
    const extra = copy();
    extra.productionSha256["Dockerfile"] = "0".repeat(64);
    assert.throws(() => validatePrepareManifest(extra), /PREPARE_PRODUCTION_PATHS_INVALID/);
    const invalid = copy();
    invalid.v8FixtureSha256 = "sha256:not-a-hash";
    assert.throws(() => validatePrepareManifest(invalid), /PREPARE_HASH_INVALID/);
});

test("reviewed P changes and exact production and test-fixture hashes pass", () => {
    assert.doesNotThrow(() => assertPrepareDiff(PREPARE_PATHS, hashes, manifest));
});

test("Dockerfile, POM, production V8, missing files and unrelated additions fail closed", () => {
    for (const path of ["Dockerfile", "pom.xml", "src/main/resources/db/migration/V8__anything.sql", ".env"]) {
        assert.throws(() => assertPrepareDiff([...PREPARE_PATHS, path], hashes, manifest), /PREPARE_DIFF_OUT_OF_SCOPE/);
    }
    assert.throws(() => assertPrepareDiff(PREPARE_PATHS.slice(1), hashes, manifest), /PREPARE_DIFF_OUT_OF_SCOPE/);
});

test("a different reader or V8 fixture is not the reviewed release", () => {
    const changed = { ...hashes, [Object.keys(manifest.productionSha256)[0]]: "0".repeat(64) };
    assert.throws(() => assertPrepareDiff(PREPARE_PATHS, changed, manifest), /PREPARE_PRODUCTION_HASH_MISMATCH/);
    assert.throws(() => assertPrepareDiff(PREPARE_PATHS, { ...hashes, [fixturePath]: "0".repeat(64) }, manifest),
        /PREPARE_V8_FIXTURE_MISMATCH/);
});

test("P CI checks out an exact source SHA but keeps the workflow and OIDC on main", () => {
    assert.match(workflow, /github\.ref == 'refs\/heads\/main'/);
    assert.match(workflow, /ref: \$\{\{ steps\.source\.outputs\.source_commit \}\}/);
    assert.match(workflow, /path: prepare-source/);
    const verify = workflow.split("\n  publish:\n")[0];
    assert.doesNotMatch(verify, /id-token: write/);
    assert.doesNotMatch(workflow, /pull_request_target|secrets\.|environment:/);
    assert.equal((workflow.match(/id-token: write/g) ?? []).length, 1);
});

test("publish consumes only its successful verify job artifact and never builds or deploys", () => {
    const publish = workflow.split("\n  publish:\n")[1];
    assert.match(publish, /needs: verify/);
    assert.match(publish, /VERIFIED_IMAGE_ID: \$\{\{ needs\.verify\.outputs\.image_id \}\}/);
    assert.match(publish, /verified-prepare-image-\$\{\{ github\.run_id \}\}-\$\{\{ github\.run_attempt \}\}/);
    assert.doesNotMatch(publish, /docker build|aws (?:ecs|rds|iam|elbv2)|batch-delete-image/);
    assert.match(publish, /registry_digest.*push_digest/s);
});

test("guarded clean P and seven packaged migrations precede upload and AWS authentication", () => {
    assert.match(workflow, /git status --porcelain\)/);
    assert.match(workflow, /productionMigrationMax:7/);
    assert.match(workflow, /prepare-git-\$PREPARE_COMMIT-run-\$GITHUB_RUN_ID-attempt-\$GITHUB_RUN_ATTEMPT/);
    assert.ok(workflow.indexOf("Validate the pinned commit") < workflow.indexOf("Verify all P Java tests"));
    assert.ok(workflow.indexOf("Check image identity and origin") < workflow.indexOf("Use the existing main-only OIDC role"));
});
