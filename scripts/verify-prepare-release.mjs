import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { appendFileSync, readFileSync } from "node:fs";
import { fileURLToPath, URL } from "node:url";
import { resolve } from "node:path";

const SOURCE_PATHS = [
    "src/main/java/lab/helpdesk/ai/job/AiJobFailureCode.java",
    "src/main/resources/static/ai-suggestion-ui.mjs"
];
const FIXTURE = "src/test/resources/rollback-compatibility/V8__allow_privacy_scan_failures.sql";
export const PREPARE_PATHS = Object.freeze([
    "compose.rollback-fixture.yaml", "deploy/rollback/prepare.md", "scripts/Verify-Week8Compose.ps1",
    "scripts/rollback/Verify-PrepareCompatibility.ps1", ...SOURCE_PATHS,
    "src/test/java/lab/helpdesk/ai/web/AiSuggestionQueryHttpIntegrationTest.java",
    "src/test/java/lab/helpdesk/deployment/RollbackPreparePostgresCompatibilityIntegrationTest.java",
    "src/test/js/rollback-prepare-container.test.mjs", "src/test/js/rollback-prepare-ui.test.mjs",
    FIXTURE, "src/test/resources/rollback-init/create-app-role.sql"
].sort());

function requireCheck(condition, code) {
    if (!condition) throw new Error(code);
}

export function validatePrepareManifest(value) {
    requireCheck(value?.repository === "HyungminYoon1/ai-helpdesk-learning-lab"
        && value.branch === "codex/week8-rollback-prepare" && value.imageRole === "rollback-prepare",
    "PREPARE_TARGET_INVALID");
    for (const commit of [value.sourceCommit, value.baseCommit]) {
        requireCheck(typeof commit === "string" && /^[a-f0-9]{40}$/.test(commit), "PREPARE_COMMIT_INVALID");
    }
    requireCheck(value.baseCommit === "2e2cc403f73972d5c0f87ab81342b776e7777f6b"
        && value.sourceCommit !== value.baseCommit, "PREPARE_BASE_INVALID");
    requireCheck(value.productionSha256 !== null && typeof value.productionSha256 === "object"
        && JSON.stringify(Object.keys(value.productionSha256).sort()) === JSON.stringify([...SOURCE_PATHS].sort()),
    "PREPARE_PRODUCTION_PATHS_INVALID");
    for (const hash of [...Object.values(value.productionSha256), value.v8FixtureSha256]) {
        requireCheck(typeof hash === "string" && /^[a-f0-9]{64}$/.test(hash), "PREPARE_HASH_INVALID");
    }
    return value;
}

export function assertPrepareDiff(paths, hashes, manifest) {
    requireCheck(JSON.stringify([...paths].sort()) === JSON.stringify(PREPARE_PATHS), "PREPARE_DIFF_OUT_OF_SCOPE");
    for (const [path, expected] of Object.entries(manifest.productionSha256)) {
        requireCheck(hashes[path] === expected, "PREPARE_PRODUCTION_HASH_MISMATCH");
    }
    requireCheck(hashes[FIXTURE] === manifest.v8FixtureSha256, "PREPARE_V8_FIXTURE_MISMATCH");
}

export function verifyPrepareSource(manifest) {
    validatePrepareManifest(manifest);
    const git = (...args) => execFileSync("git", args, { stdio: ["ignore", "pipe", "pipe"], maxBuffer: 4 * 1024 * 1024 });
    const gitText = (...args) => git(...args).toString("utf8").trim();
    requireCheck(gitText("rev-parse", `${manifest.sourceCommit}^{commit}`) === manifest.sourceCommit,
        "PREPARE_SOURCE_NOT_COMMIT");
    git("merge-base", "--is-ancestor", manifest.baseCommit, manifest.sourceCommit);
    git("merge-base", "--is-ancestor", manifest.sourceCommit, `refs/remotes/origin/${manifest.branch}`);
    const changes = gitText("diff", "--name-status", "--no-renames", manifest.baseCommit, manifest.sourceCommit)
        .split("\n").map((line) => line.split("\t"));
    requireCheck(changes.every(([status, path]) => ["A", "M"].includes(status) && path), "PREPARE_CHANGE_TYPE_INVALID");
    const hashes = {};
    for (const path of [...SOURCE_PATHS, FIXTURE]) {
        hashes[path] = createHash("sha256").update(git("show", `${manifest.sourceCommit}:${path}`)).digest("hex");
    }
    assertPrepareDiff(changes.map(([, path]) => path), hashes, manifest);
    requireCheck(gitText("ls-tree", "--name-only", `${manifest.sourceCommit}:src/main/resources/db/migration`)
        .split("\n").every((name) => /^V[1-7]__.+\.sql$/.test(name)), "PREPARE_PRODUCTION_V8_FORBIDDEN");
    return { sourceCommit: manifest.sourceCommit, baseCommit: manifest.baseCommit, imageRole: manifest.imageRole };
}

function main() {
    try {
        if (process.env.GITHUB_ACTIONS === "true") {
            requireCheck(process.env.GITHUB_REPOSITORY === "HyungminYoon1/ai-helpdesk-learning-lab"
                && process.env.GITHUB_EVENT_NAME === "workflow_dispatch" && process.env.GITHUB_REF === "refs/heads/main",
            "PREPARE_TRUSTED_MAIN_DISPATCH_REQUIRED");
        }
        const manifest = JSON.parse(readFileSync(new URL("../deploy/rollback/prepare-release.json", import.meta.url), "utf8"));
        const result = verifyPrepareSource(manifest);
        if (process.env.GITHUB_OUTPUT) {
            appendFileSync(process.env.GITHUB_OUTPUT,
                `source_commit=${result.sourceCommit}\nbase_commit=${result.baseCommit}\n`, "utf8");
        }
        console.log(JSON.stringify({ evidence: "PREPARE_SOURCE_GUARD", outcome: "PASS", ...result }));
    } catch {
        // No raw git error, environment value, remote credential or request body.
        console.error("PREPARE_SOURCE_GUARD_FAILED");
        process.exitCode = 1;
    }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
