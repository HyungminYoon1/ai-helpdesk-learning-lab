import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { URL } from "node:url";

const source = readFileSync(new URL("../../../scripts/Verify-Week8Compose.ps1", import.meta.url), "utf8");
const override = readFileSync(new URL("../../../compose.deployment-probe.yaml", import.meta.url), "utf8");

// These are source/configuration safeguards, not live Docker or performance tests.
test("deployment probe overrides only the app profile without replacing the database", () => {
  assert.equal(/^services:\n  app:/m.test(override), true);
  assert.equal(/^  db:/m.test(override), false);
  assert.equal(override.includes("SPRING_PROFILES_ACTIVE: postgres,deployment"), true);
  assert.equal(override.includes("HELPDESK_DEPLOYMENT_USER_PASSWORD: ${HELPDESK_LOCAL_USER_PASSWORD:?"), true);
  assert.equal(override.includes('HELPDESK_LOCAL_USER_PASSWORD: ""'), true);
});

test("deployment probe explicitly disables both AI components and does not forward an API key", () => {
  assert.equal(override.includes('HELPDESK_AI_PROVIDER_ENABLED: "false"')
    && override.includes('HELPDESK_AI_WORKER_ENABLED: "false"'), true);
  assert.equal(/OPENAI_API_KEY|HELPDESK_AI_PROVIDER_KEY/.test(override), false);
});

test("deployment probe caps memory and swap with the same input and caps CPU", () => {
  const memoryInput = "${HELPDESK_PROBE_MEMORY_MIB:?Probe memory limit is required}m";
  assert.equal(override.includes(`mem_limit: ${memoryInput}`)
    && override.includes(`memswap_limit: ${memoryInput}`), true);
  assert.equal(override.includes("cpus: ${HELPDESK_PROBE_CPUS:?"), true);
  assert.equal(source.includes("'RESOURCE_LIMIT_NOT_APPLIED'")
    && source.includes("'CGROUP_LIMIT_NOT_APPLIED'"), true);
});

test("resource measurement remains opt-in and uses the selected existing image", () => {
  assert.equal(source.includes("[switch]$DeploymentResourceProbe"), true);
  assert.equal(source.includes("[string]$ImageTag = 'helpdesk:week8-build-baseline'"), true);
  assert.equal(source.includes("SetEnvironmentVariable('HELPDESK_LOCAL_IMAGE', $ImageTag, 'Process')"), true);
  assert.equal(source.includes("'up', '-d', '--no-build'"), true);
  assert.equal(source.includes("'RESOURCE_PROBE_CANNOT_MIX_RESTART_CASES'"), true);
});

test("probe distinguishes kernel memory peak and OOM from a sampled display or full load test", () => {
  assert.equal(source.includes("'/sys/fs/cgroup/memory.peak'")
    && source.includes("'/sys/fs/cgroup/memory.events'")
    && source.includes("'/sys/fs/cgroup/cpu.stat'"), true);
  assert.equal(source.includes("$after.Counters.oom -eq 0 -and $after.Counters.oom_kill -eq 0"), true);
  assert.equal(source.includes("queryMode = 'SEQUENTIAL_AUTHENTICATED_TICKET_READS'")
    && source.includes("fullLoadTest = $false"), true);
});

test("probe preserves environment and volumes and reports only safe results", () => {
  assert.equal(source.includes("'HELPDESK_PROBE_MEMORY_MIB', 'HELPDESK_PROBE_CPUS'"), true);
  assert.equal(source.includes("$report.environmentRestored = $environmentRestored"), true);
  assert.equal(source.includes("$report.volumeDeleted = $false"), true);
  assert.equal(/'down'|'--volumes'|Remove-Item/.test(source), false);
  assert.equal(source.includes("$report.failureCode = 'VERIFICATION_FAILED'"), true);
});
