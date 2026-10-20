import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { URL } from "node:url";

const read = (path) => readFileSync(new URL(`../../../${path}`, import.meta.url), "utf8");
const runner = read("scripts/Verify-Week8SyntheticRuntime.ps1");
const compose = read("compose.synthetic-runtime-probe.yaml");
const experiment = read("src/test/java/experiment/helpdesk/runtime/SyntheticRuntimeResourceExperiment.java");
const bridge = read("src/test/java/lab/helpdesk/ai/provider/SyntheticRuntimeProviderBridge.java");

// Source safeguards only. The Docker experiment separately verifies runtime behavior.
test("synthetic runtime has an internal network and no host ports or daemon socket", () => {
  assert.match(compose, /internal: true/);
  assert.doesNotMatch(compose, /\bports:|docker\.sock|network_mode: host/);
  assert.match(runner, /CREATE_AND_VERIFY_ISOLATION/);
  assert.match(runner, /\.Internal/);
  assert.match(runner, /START_DATABASE/);
});

test("only a controlled loopback provider replaces the remote dependency", () => {
  assert.match(compose, /HELPDESK_AI_PROVIDER_ENABLED: "false"/);
  assert.match(compose, /HELPDESK_AI_WORKER_ENABLED: "true"/);
  assert.match(bridge, /http:\/\/127\.0\.0\.1:/);
  assert.doesNotMatch(bridge, /fromEnv|System\.getenv|OPENAI_API_KEY/);
  assert.match(experiment, /mainProviderConfigurationUsed", false/);
  assert.doesNotMatch(experiment, /\.runOnce\(/);
});

test("application classes come from the image, not a mounted main class directory", () => {
  assert.doesNotMatch(runner + compose, /target\/classes|target\\classes/);
  assert.match(runner, /SyntheticRuntimeResourceExperiment\*\.class/);
  assert.match(runner, /SyntheticRuntimeProviderBridge\.class/);
  assert.match(experiment, /requirePackaged\(AiSuggestionJobWorker\.class\)/);
  assert.match(experiment, /\/app\/helpdesk\.jar/);
});

test("resource limits include swap and kernel peak instead of equating heap with total memory", () => {
  assert.match(compose, /mem_limit: \$\{HELPDESK_RUNTIME_PROBE_MEMORY:/);
  assert.match(compose, /memswap_limit: \$\{HELPDESK_RUNTIME_PROBE_MEMORY:/);
  assert.match(compose, /cpus: \$\{HELPDESK_RUNTIME_PROBE_CPUS:/);
  assert.match(experiment, /memory\.current/);
  assert.match(experiment, /memory\.peak/);
  assert.match(experiment, /memory\.events\.oom_kill/);
});

test("the experiment checks real rows, masked HTTP and process/thread cleanup after repeated jobs", () => {
  assert.match(experiment, /index < 3/);
  assert.match(experiment, /FROM ticket_suggestions WHERE job_id/);
  assert.match(experiment, /m\.body = \?/);
  assert.match(experiment, /j\.reserved_generation_count = 1/);
  assert.match(experiment, /validMaskedRequests\.get\(\) == 3/);
  assert.match(experiment, /ProcessHandle\.current\(\)\.descendants\(\)/);
  assert.match(experiment, /helpdesk-privacy-io-/);
});

test("synthetic secrets are file-mounted and caller key variables are not changed", () => {
  assert.match(compose, /SPRING_CONFIG_IMPORT: configtree:\/run\/secrets\//);
  assert.match(compose, /read_only: true/);
  assert.match(runner, /COMPOSE_DISABLE_ENV_FILE/);
  assert.match(runner, /start\.Environment\.Remove\(\$name\)/);
  assert.doesNotMatch(runner, /SetEnvironmentVariable|GetEnvironmentVariable|Get-Content.*\.env/);
});

test("cleanup is confined to the UUID project and never removes images or volumes", () => {
  assert.match(runner, /label=com\.docker\.compose\.project=/);
  assert.match(runner, /GetDirectoryName\(\$resolvedSecrets\) -eq \$resolvedRun/);
  assert.match(runner, /GetFileName\(\$resolvedRun\) -eq \$runId/);
  assert.match(runner, /volumeDeleted = \$false/);
  assert.doesNotMatch(runner, /'prune'|'--volumes'|'volume', 'rm'|'image', 'rm'/);
});

test("failures publish fixed stage/codes without raw logs or exception text", () => {
  assert.match(runner, /ErrorOutput = \$stderr\.GetAwaiter/);
  assert.match(runner, /PROBE_RUNNER_FAILED/);
  assert.match(experiment, /SYNTHETIC_RUNTIME_VERIFICATION_FAILED/);
  assert.doesNotMatch(experiment, /printStackTrace|failure\.getMessage|failure\.toString/);
  assert.doesNotMatch(runner, /Write-Output \$logs|Write-Error|Format-List \*/);
});

test("failure injection is an explicit test-only mode, never a Main input option", () => {
  assert.match(runner, /ValidateSet\('NORMAL', 'TIMEOUT', 'NON_ZERO_EXIT'\)/);
  assert.match(compose, /HELPDESK_RUNTIME_PROBE_SCAN_MODE: \$\{HELPDESK_RUNTIME_PROBE_SCAN_MODE:/);
  assert.match(experiment, /@Qualifier\("aiPrivacyScanner"\)/);
  assert.match(experiment, /if \(mode == ScanMode\.NORMAL\) return packagedScanner/);
  assert.doesNotMatch(read("src/main/java/lab/helpdesk/ai/input/synthetic/SyntheticDeploymentPrivacyConfiguration.java"),
    /probe\.scan-mode|FaultScannerProgram|NON_ZERO_EXIT/);
});

test("valid JSON followed by timeout or nonzero exit cannot count as a successful scan", () => {
  assert.match(experiment, /SyntheticPrivacyScannerProgram\.main\(new String\[0\]\)/);
  assert.match(experiment, /StandardOpenOption\.CREATE_NEW/);
  assert.match(experiment, /System\.exit\(7\)/);
  assert.match(experiment, /Thread\.sleep\(120_000\)/);
  assert.match(experiment, /PRIVACY_SCAN_TIMEOUT" : "ADAPTER_CONFIGURATION_ERROR/);
  assert.match(experiment, /childReachedAfterJsonWrite", true/);
  assert.match(experiment, /ownedChildConfirmedDead", true/);
  assert.match(experiment, /fixture\.requests\.get\(\) == 0/);
});

test("failed runs preserve reservations and independently recheck project cleanup", () => {
  assert.match(experiment, /bool_and\(current_attempt = 1/);
  assert.match(experiment, /reserved_generation_count = 1 AND status = 'FAILED'/);
  assert.match(runner, /remainingProjectContainers = 0/);
  assert.match(runner, /remainingProjectNetworks = 0/);
  assert.match(runner, /syntheticSecretFilesRemoved = \$true/);
  assert.match(runner, /\$probe\.scanMode -eq \$ScanMode/);
});
