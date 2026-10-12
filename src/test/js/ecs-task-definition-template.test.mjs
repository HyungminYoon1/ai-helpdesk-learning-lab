import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { URL } from "node:url";

let draft;
try {
  draft = JSON.parse(readFileSync(
    new URL("../../../deploy/ecs/task-definition.template.json", import.meta.url),
    "utf8",
  ));
} catch {
  // Do not retain parser snippets if someone accidentally adds sensitive data.
  throw new Error("ECS_DRAFT_JSON_PARSE_FAILED");
}
const container = draft.containerDefinitions?.[0];
const environment = new Map((container?.environment ?? []).map(({ name, value }) => [name, value]));
const placeholder = (value) => typeof value === "string" && /^<[A-Z0-9_]+>$/.test(value);

test("ECS draft selects one Linux amd64 Fargate container with awsvpc", () => {
  assert.equal(draft.requiresCompatibilities?.length === 1
    && draft.requiresCompatibilities[0] === "FARGATE", true);
  assert.equal(draft.networkMode === "awsvpc", true);
  assert.equal(draft.runtimePlatform?.operatingSystemFamily === "LINUX"
    && draft.runtimePlatform.cpuArchitecture === "X86_64", true);
  assert.equal(draft.containerDefinitions?.length === 1
    && container.name === "helpdesk" && container.essential === true, true);
  assert.equal(container.portMappings?.length === 1
    && container.portMappings[0].containerPort === 8080
    && container.portMappings[0].protocol === "tcp", true);
  assert.equal(container.stopTimeout === 45, true);
});

test("ECS draft explicitly uses deployment users and disables paid AI processing", () => {
  assert.equal(environment.get("SPRING_PROFILES_ACTIVE") === "postgres,deployment", true);
  assert.equal(environment.get("HELPDESK_AI_PROVIDER_ENABLED") === "false", true);
  assert.equal(environment.get("HELPDESK_AI_WORKER_ENABLED") === "false", true);
  assert.equal(environment.has("HELPDESK_AI_PROVIDER_KEY")
    || container.secrets?.some(({ name }) => name === "HELPDESK_AI_PROVIDER_KEY"), false);
});

test("ECS draft maps all six credential inputs to the agreed SSM parameters", () => {
  const expectedParameters = new Map([
    ["SPRING_DATASOURCE_USERNAME", "db/username"],
    ["SPRING_DATASOURCE_PASSWORD", "db/password"],
    ["HELPDESK_DEPLOYMENT_USER_USERNAME", "user/username"],
    ["HELPDESK_DEPLOYMENT_USER_PASSWORD", "user/password"],
    ["HELPDESK_DEPLOYMENT_AGENT_USERNAME", "agent/username"],
    ["HELPDESK_DEPLOYMENT_AGENT_PASSWORD", "agent/password"],
  ]);
  const secrets = container.secrets ?? [];
  assert.equal(secrets.length === expectedParameters.size
    && new Set(secrets.map(({ name }) => name)).size === expectedParameters.size, true);
  assert.equal(secrets.every(({ name, valueFrom }) =>
    expectedParameters.has(name)
    && valueFrom === `arn:aws:ssm:ap-northeast-2:<AWS_ACCOUNT_ID>:parameter/helpdesk/learning/${expectedParameters.get(name)}`), true);
  assert.equal([...environment.keys()].some((name) => expectedParameters.has(name)
    || /PASSWORD|USERNAME|TOKEN|SECRET|API_KEY/.test(name)), false);
});

test("ECS draft keeps HTTPS cookie and forwarded settings in nonsecret application JSON", () => {
  let settings;
  try {
    settings = JSON.parse(environment.get("SPRING_APPLICATION_JSON"));
  } catch {
    throw new Error("ECS_RUNTIME_SETTINGS_JSON_PARSE_FAILED");
  }
  assert.equal(Object.keys(settings).length === 1 && Object.hasOwn(settings, "server"), true);
  const server = settings.server;
  const cookie = server?.servlet?.session?.cookie;
  assert.equal(server?.["forward-headers-strategy"] === "native", true);
  assert.equal(cookie?.secure === true && cookie?.["http-only"] === true
    && cookie?.["same-site"] === "lax", true);
});

test("ECS draft leaves cloud-specific inputs unresolved and does not invent a task role", () => {
  assert.equal(placeholder(draft.cpu) && placeholder(draft.memory), true);
  assert.equal(placeholder(draft.executionRoleArn) && placeholder(container.image), true);
  const jdbcUrl = environment.get("SPRING_DATASOURCE_URL");
  assert.equal(typeof jdbcUrl === "string" && jdbcUrl.includes("<RDS_ENDPOINT>")
    && jdbcUrl.includes("<DB_NAME>"), true);
  assert.equal(Object.hasOwn(draft, "taskRoleArn"), false);
  assert.equal(Object.hasOwn(container, "command") || Object.hasOwn(container, "entryPoint"), false);
});

test("ECS draft requires RDS certificate and hostname validation with the bundled CA", () => {
  assert.equal(environment.get("SPRING_DATASOURCE_URL") ===
    "jdbc:postgresql://<RDS_ENDPOINT>:5432/<DB_NAME>?sslmode=verify-full&sslrootcert=/app/certs/rds-ca-bundle.pem", true);
});

test("ECS draft uses a precreated Seoul log group without automatic group creation", () => {
  const logs = container.logConfiguration;
  assert.equal(logs?.logDriver === "awslogs", true);
  assert.equal(logs?.options?.["awslogs-region"] === "ap-northeast-2", true);
  assert.equal(logs?.options?.["awslogs-create-group"] === "false", true);
  assert.equal(placeholder(logs?.options?.["awslogs-group"]), true);
});
