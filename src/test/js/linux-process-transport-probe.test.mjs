import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { URL } from "node:url";

const script = readFileSync(new URL("../../../scripts/Verify-Week8LinuxProcessTransport.ps1", import.meta.url), "utf8");
const suite = readFileSync(new URL("../java/experiment/helpdesk/process/LinuxProcessTransportSuite.java", import.meta.url), "utf8");
const pom = readFileSync(new URL("../../../pom.xml", import.meta.url), "utf8");

// Source safeguards only; the separate Docker run establishes Linux behavior.
test("Linux launcher is test-scoped and selects only the existing transport suite", () => {
  assert.match(pom, /<artifactId>junit-platform-launcher<\/artifactId>\s*<version>\$\{junit-jupiter.version\}<\/version>\s*<scope>test<\/scope>/);
  assert.match(suite, /selectClass\("lab.helpdesk.ai.input.ProcessAiPrivacyScannerTest"\)/);
  assert.doesNotMatch(suite, /selectPackage|SpringApplication|@Configuration|@Bean/);
});

test("probe fixes a nonroot read-only network-isolated runtime without a Docker socket", () => {
  assert.ok(script.includes("--read-only --network none --user '10001:10001'"));
  assert.ok(script.includes("--cap-drop ALL --security-opt no-new-privileges --pids-limit 128"));
  assert.equal((script.match(/target=\/opt\/helpdesk\/[a-z]+,readonly/g) ?? []).length, 3);
  assert.doesNotMatch(script, /docker\.sock|--privileged|--publish|--env-file|--env\s|\$env:/i);
});

test("runtime limits are verified and execution has an outer deadline", () => {
  assert.ok(script.includes("--memory 512m --memory-swap 512m --cpus $cpuArgument"));
  assert.ok(script.includes("LINUX_PROCESS_CONSTRAINTS_NOT_APPLIED"));
  assert.ok(script.includes("$elapsed.Elapsed.TotalSeconds -ge 120"));
  assert.ok(script.includes("LINUX_PROCESS_OUTER_TIMEOUT"));
});

test("cleanup uses only the exact created container ID and never deletes images or volumes", () => {
  assert.ok(script.includes("$containerId = [string]$created[0]"));
  assert.ok(script.includes("docker kill --signal KILL $containerId"));
  assert.ok(script.includes("docker rm $containerId"));
  assert.doesNotMatch(script, /docker (?:system|image|volume) (?:prune|rm)|Remove-Item|--volumes/);
});

test("test reports omit exception payloads and reject skipped or absent tests", () => {
  assert.ok(suite.includes("getTestsFoundCount() >= 24"));
  assert.ok(suite.includes("summary.getTestsFoundCount() == summary.getTestsSucceededCount()"));
  assert.ok(suite.includes("summary.getTestsSkippedCount() == 0"));
  assert.doesNotMatch(suite, /printStackTrace|getMessage\(|printFailuresTo|printTo\(/);
  assert.ok(script.includes("LINUX_PROCESS_TEST_RESULT "));
});

test("evidence separates the runtime harness from Spring, PostgreSQL, AI and a released image", () => {
  for (const evidence of ["workingTreeDirty = $workingTreeDirty", "transportSha256 = $transportSha256",
    "providerCalls = 0", "postgresUsed = $false", "springApplicationStarted = $false"]) {
    assert.ok(script.includes(evidence));
  }
  assert.ok(script.includes("dependency:copy-dependencies"));
  assert.doesNotMatch(script, /spring-boot:run|docker push|aws\s/);
});
