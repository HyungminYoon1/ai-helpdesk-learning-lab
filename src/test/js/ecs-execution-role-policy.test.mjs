import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { URL } from "node:url";

function readTemplate(path) {
  try {
    return JSON.parse(readFileSync(new URL(path, import.meta.url), "utf8"));
  } catch {
    // Never copy a parser snippet that could contain an accidentally added secret.
    throw new Error("ECS_POLICY_TEMPLATE_JSON_PARSE_FAILED");
  }
}

const policy = readTemplate("../../../deploy/ecs/execution-role-policy.template.json");
const trust = readTemplate("../../../deploy/ecs/execution-role-trust.template.json");
const task = readTemplate("../../../deploy/ecs/task-definition.template.json");
const statements = policy.Statement;
const statement = (sid) => statements.find((entry) => entry.Sid === sid);

test("execution trust draft allows only the ECS tasks service to assume the role", () => {
  assert.equal(trust.Version === "2012-10-17" && trust.Statement.length === 1, true);
  const entry = trust.Statement[0];
  assert.equal(entry.Effect === "Allow" && entry.Action === "sts:AssumeRole", true);
  assert.equal(Object.keys(entry.Principal).length === 1
    && entry.Principal.Service === "ecs-tasks.amazonaws.com", true);
});

test("execution trust draft limits the service request to the agreed account and region", () => {
  const condition = trust.Statement[0].Condition;
  assert.equal(condition.StringEquals["aws:SourceAccount"] === "<AWS_ACCOUNT_ID>", true);
  assert.equal(condition.ArnLike["aws:SourceArn"] ===
    "arn:aws:ecs:ap-northeast-2:<AWS_ACCOUNT_ID>:*", true);
  assert.equal(Object.keys(condition).length === 2
    && Object.keys(condition.StringEquals).length === 1
    && Object.keys(condition.ArnLike).length === 1, true);
});

test("execution permission draft contains only four explicit allow statements", () => {
  assert.equal(policy.Version === "2012-10-17" && statements.length === 4, true);
  assert.equal(new Set(statements.map(({ Sid }) => Sid)).size === 4, true);
  assert.equal(statements.every((entry) => entry.Effect === "Allow"
    && Object.keys(entry).every((key) => ["Sid", "Effect", "Action", "Resource"].includes(key))), true);
});

test("execution role reads exactly the six SSM references used by the task", () => {
  const ssm = statement("ReadSixHelpdeskParameters");
  const references = task.containerDefinitions[0].secrets.map(({ valueFrom }) => valueFrom);
  assert.equal(ssm.Action.length === 1 && ssm.Action[0] === "ssm:GetParameters", true);
  assert.equal(ssm.Resource.length === 6 && new Set(ssm.Resource).size === 6, true);
  assert.equal(ssm.Resource.every((resource) => references.includes(resource)
    && /^arn:aws:ssm:ap-northeast-2:<AWS_ACCOUNT_ID>:parameter\/helpdesk\/learning\/(db|user|agent)\/(username|password)$/.test(resource)), true);
});

test("only ECR registry authentication uses an unscoped resource", () => {
  const authentication = statement("EcrRegistryAuthentication");
  assert.equal(authentication.Action.length === 1
    && authentication.Action[0] === "ecr:GetAuthorizationToken"
    && authentication.Resource === "*", true);
  assert.equal(statements.filter(({ Resource }) => Resource === "*").length === 1, true);
});

test("execution role permits pull operations on only the Helpdesk ECR repository", () => {
  const pull = statement("PullVerifiedHelpdeskImage");
  const actions = new Set(pull.Action);
  assert.equal(actions.size === 3
    && actions.has("ecr:BatchCheckLayerAvailability")
    && actions.has("ecr:BatchGetImage")
    && actions.has("ecr:GetDownloadUrlForLayer"), true);
  assert.equal(pull.Resource === "arn:aws:ecr:ap-northeast-2:<AWS_ACCOUNT_ID>:repository/ai-helpdesk-learning-lab", true);
});

test("execution role writes streams in one precreated log group without creating groups", () => {
  const logs = statement("WriteHelpdeskLogStreams");
  assert.equal(logs.Action.length === 2
    && logs.Action.includes("logs:CreateLogStream") && logs.Action.includes("logs:PutLogEvents"), true);
  assert.equal(logs.Resource === "arn:aws:logs:ap-northeast-2:<AWS_ACCOUNT_ID>:log-group:<PRECREATED_LOG_GROUP_NAME>:log-stream:*", true);
  assert.equal(task.containerDefinitions[0].logConfiguration.options["awslogs-create-group"] === "false", true);
});

test("execution role adds no secret mutation, broad decryption, AI or task-code permissions", () => {
  const allowed = new Set([
    "ecr:GetAuthorizationToken", "ecr:BatchCheckLayerAvailability",
    "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer",
    "logs:CreateLogStream", "logs:PutLogEvents", "ssm:GetParameters",
  ]);
  assert.equal(statements.every(({ Action }) => Array.isArray(Action)
    && Action.every((action) => allowed.has(action))), true);
  assert.equal(Object.hasOwn(task, "taskRoleArn"), false);
  assert.equal(statement("ReadSixHelpdeskParameters").Resource
    .some((resource) => /\*|secretsmanager:|kms:/.test(resource)), false);
});
