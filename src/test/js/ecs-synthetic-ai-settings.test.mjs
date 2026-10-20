import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { URL } from "node:url";

function readTemplate(name) {
  try {
    return JSON.parse(readFileSync(new URL(`../../../deploy/ecs/${name}`, import.meta.url), "utf8"));
  } catch {
    throw new Error("SYNTHETIC_ECS_TEMPLATE_INVALID");
  }
}

const settings = readTemplate("synthetic-ai-container-settings.template.json");
const policy = readTemplate("synthetic-ai-parameter-read-policy.template.json");
const baseline = readTemplate("task-definition.template.json");
const environment = new Map(settings.environment.map(({ name, value }) => [name, value]));
const keyReference = "arn:aws:ssm:ap-northeast-2:<AWS_ACCOUNT_ID>:parameter/helpdesk/learning/ai/provider-key";

test("synthetic settings are an explicit container fragment, not a runnable task definition", () => {
  assert.deepEqual(Object.keys(settings).sort(), ["environment", "secrets"]);
  assert.equal(environment.size, settings.environment.length);
  assert.equal(environment.get("SPRING_PROFILES_ACTIVE"), "postgres,deployment,synthetic-ai");
  for (const name of ["HELPDESK_AI_PROVIDER_ENABLED", "HELPDESK_AI_WORKER_ENABLED",
    "HELPDESK_AI_SYNTHETIC_SCAN_ENABLED"]) {
    assert.equal(environment.get(name), "true");
  }
});

test("only newly received jobs use the single-generation policy settings", () => {
  assert.equal(environment.get("HELPDESK_AI_JOB_POLICYVERSION"), "cloud-synthetic-once-v1");
  assert.equal(environment.get("HELPDESK_AI_JOB_MAXGENERATIONATTEMPTS"), "1");
  assert.equal(environment.get("HELPDESK_AI_JOB_MAXOUTPUTREPAIRATTEMPTS"), "0");
  assert.equal(environment.has("HELPDESK_AI_JOB_MAX_GENERATION_ATTEMPTS"), false);
});

test("synthetic settings use the packaged scanner and explicit existing limits", () => {
  assert.equal(environment.get("HELPDESK_AI_SYNTHETIC_SCAN_SCANNER_JAR"), "/app/helpdesk.jar");
  assert.equal(environment.get("HELPDESK_AI_SYNTHETIC_SCAN_TIMEOUT_MS"), "20000");
  assert.equal(environment.get("HELPDESK_AI_SYNTHETIC_SCAN_CLEANUP_TIMEOUT_MS"), "3000");
  assert.equal(environment.get("HELPDESK_AI_PROVIDER_MAX_SUMMARY_CODE_POINTS"), "200");
});

test("the key is one exact SSM reference and is never a public environment value", () => {
  assert.deepEqual(settings.secrets, [{ name: "HELPDESK_AI_PROVIDER_KEY", valueFrom: keyReference }]);
  assert.equal([...environment.keys()].some((name) => /KEY|PASSWORD|TOKEN|SECRET/.test(name)), false);
  assert.equal(environment.has("OPENAI_API_KEY"), false);
});

test("the optional permission fragment reads one key without mutation or broad decryption", () => {
  assert.equal(policy.Version, "2012-10-17");
  assert.deepEqual(policy.Statement, [{
    Sid: "ReadSyntheticAiProviderKey",
    Effect: "Allow",
    Action: ["ssm:GetParameters"],
    Resource: keyReference,
  }]);
  assert.equal(keyReference.includes("*"), false);
});

test("the default task still disables AI and keeps its original six references", () => {
  const container = baseline.containerDefinitions[0];
  const defaults = new Map(container.environment.map(({ name, value }) => [name, value]));
  assert.equal(defaults.get("SPRING_PROFILES_ACTIVE"), "postgres,deployment");
  assert.equal(defaults.get("HELPDESK_AI_PROVIDER_ENABLED"), "false");
  assert.equal(defaults.get("HELPDESK_AI_WORKER_ENABLED"), "false");
  assert.equal(container.secrets.length, 6);
  assert.equal(container.secrets.some(({ valueFrom }) => valueFrom === keyReference), false);
});

test("merging the fragment preserves database, cookies, image and original secret references", () => {
  const container = baseline.containerDefinitions[0];
  const original = JSON.parse(JSON.stringify(container));
  const merged = JSON.parse(JSON.stringify(container));
  merged.environment = [...new Map([...container.environment, ...settings.environment]
    .map((entry) => [entry.name, entry])).values()];
  merged.secrets = [...new Map([...container.secrets, ...settings.secrets]
    .map((entry) => [entry.name, entry])).values()];
  const mergedEnvironment = new Map(merged.environment.map(({ name, value }) => [name, value]));
  for (const name of ["SPRING_DATASOURCE_URL", "SPRING_APPLICATION_JSON"]) {
    assert.equal(mergedEnvironment.get(name),
      container.environment.find((entry) => entry.name === name).value);
  }
  assert.deepEqual(merged.secrets.slice(0, 6), container.secrets);
  assert.equal(merged.secrets.length, 7);
  for (const name of ["image", "portMappings", "stopTimeout", "logConfiguration"]) {
    assert.deepEqual(merged[name], container[name]);
  }
  assert.deepEqual(container, original);
});
