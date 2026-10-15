import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { URL } from "node:url";

const readPolicy = (name) => {
  try {
    return JSON.parse(readFileSync(new URL(
      `../../../deploy/iam/deployer-${name}-policy.template.json`, import.meta.url,
    ), "utf8"));
  } catch {
    throw new Error("DEPLOYER_POLICY_TEMPLATE_PARSE_FAILED");
  }
};

const policies = ["foundation", "runtime", "edge", "rds-console-read"].map(readPolicy);
const statements = policies.flatMap((policy) => policy.Statement);
const statement = (sid) => statements.find((entry) => entry.Sid === sid);
const allowed = statements.filter((entry) => entry.Effect === "Allow");

test("each deployer policy is valid JSON and fits the managed policy size limit", () => {
  assert.equal(policies.every((policy) => policy.Version === "2012-10-17"
    && JSON.stringify(policy).replaceAll("<AWS_ACCOUNT_ID>", "000000000000")
      .replaceAll("<LEARNING_VPC_ID>", "vpc-00000000000000000")
      .replaceAll("<LEARNING_TARGET_GROUP_ID>", "0000000000000000").length <= 6144), true);
  assert.equal(new Set(statements.map((entry) => entry.Sid)).size === statements.length, true);
  assert.equal(statements.every((entry) => !Object.hasOwn(entry, "Principal")
    && !Object.hasOwn(entry, "NotResource")), true);
});

test("deployer allow actions exclude resource deletion and wildcard actions with only scoped target deregistration", () => {
  assert.equal(allowed.every((entry) => Array.isArray(entry.Action)
    && entry.Action.every((action) => !action.includes("*")
      && (!/:Delete|:Deregister|:Terminate|:Remove/.test(action)
        || action === "elasticloadbalancing:DeregisterTargets"))), true);
});

test("target deregistration permits only the selected group with explicit MFA Seoul and Project conditions", () => {
  const rules = allowed.filter((entry) => entry.Action.includes("elasticloadbalancing:DeregisterTargets"));
  assert.deepEqual(rules, [{
    Sid: "DeregisterOnlyLearningTargets",
    Effect: "Allow",
    Action: ["elasticloadbalancing:DeregisterTargets"],
    Resource: "arn:aws:elasticloadbalancing:ap-northeast-2:<AWS_ACCOUNT_ID>:targetgroup/helpdesk-learning/<LEARNING_TARGET_GROUP_ID>",
    Condition: {
      StringEquals: {
        "aws:RequestedRegion": "ap-northeast-2",
        "aws:ResourceTag/Project": "helpdesk-learning",
      },
      Bool: { "aws:MultiFactorAuthPresent": "true" },
    },
  }]);
  assert.deepEqual(readPolicy("edge").Statement.find((entry) =>
    entry.Sid === "DeregisterOnlyLearningTargets"), rules[0]);
});

test("MFA is required for direct deployment calls, with only self enrollment exceptions", () => {
  const rule = statement("RequireMfaExceptSelfEnrollment");
  assert.equal(rule.Effect === "Deny" && rule.Resource === "*", true);
  assert.deepEqual(rule.Condition.BoolIfExists, {
    "aws:MultiFactorAuthPresent": "false", "aws:ViaAWSService": "false",
  });
  assert.equal(rule.NotAction.includes("iam:EnableMFADevice")
    && !rule.NotAction.includes("iam:ChangePassword")
    && !rule.NotAction.includes("iam:CreateAccessKey")
    && !rule.NotAction.includes("ecs:RunTask"), true);
});

test("self credential permissions are confined to the selected user and MFA device", () => {
  assert.equal(statement("ManageOnlyOwnMfaAndPassword").Resource ===
    "arn:aws:iam::<AWS_ACCOUNT_ID>:user/helpdesk-deployer", true);
  assert.equal(statement("CreateOnlyOwnVirtualMfa").Resource ===
    "arn:aws:iam::<AWS_ACCOUNT_ID>:mfa/helpdesk-deployer", true);
  assert.equal(allowed.flatMap((entry) => entry.Action).some((action) =>
    ["iam:CreateAccessKey", "iam:DeactivateMFADevice", "iam:DeleteVirtualMFADevice"].includes(action)), false);
});

test("regional deployment is restricted to Seoul without blocking global IAM enrollment", () => {
  const rule = statement("RestrictRegionalOperationsToSeoul");
  assert.equal(rule.Effect === "Deny"
    && rule.Condition.StringNotEquals["aws:RequestedRegion"] === "ap-northeast-2"
    && rule.NotAction.includes("iam:*"), true);
});

test("Outposts access is one metadata list action behind the MFA and Seoul denials", () => {
  assert.deepEqual(allowed.flatMap((entry) => entry.Action)
    .filter((action) => action.startsWith("outposts:")), ["outposts:ListOutposts"]);
  const reader = statement("ReadMeta");
  assert.equal(reader.Resource, "*");
  assert.equal(reader.Action.includes("outposts:ListOutposts"), true);
  for (const sid of ["RequireMfaExceptSelfEnrollment", "RestrictRegionalOperationsToSeoul"]) {
    const denial = statement(sid);
    assert.equal(denial.Effect, "Deny");
    assert.equal(denial.Resource, "*");
    assert.equal(denial.NotAction.some((action) => action.startsWith("outposts:")), false);
  }
  assert.equal(statement("RequireMfaExceptSelfEnrollment").Condition.BoolIfExists["aws:MultiFactorAuthPresent"],
    "false");
  assert.equal(statement("RestrictRegionalOperationsToSeoul").Condition.StringNotEquals["aws:RequestedRegion"],
    "ap-northeast-2");
});

test("only the existing execution role can be passed to the ECS tasks service", () => {
  const rule = statement("PassOnlyExecutionRoleToEcs");
  assert.deepEqual(rule.Action, ["iam:PassRole"]);
  assert.equal(rule.Resource === "arn:aws:iam::<AWS_ACCOUNT_ID>:role/helpdesk-ecs-execution", true);
  assert.deepEqual(rule.Condition, { StringEquals: { "iam:PassedToService": "ecs-tasks.amazonaws.com" } });
});

test("RDS Console metadata reads contain only the approved lookup actions", () => {
  const reader = statement("ReadMeta");
  assert.equal(reader.Effect, "Allow");
  assert.equal(reader.Resource, "*");
  assert.deepEqual(reader.Action, [
    "ecs:ListClusters", "ecs:ListServices", "ecs:ListTasks", "ecs:ListTaskDefinitions",
    "ecs:DescribeTaskDefinition", "rds:DescribeDBInstances", "rds:DescribeDBSubnetGroups",
    "rds:DescribeDBParameterGroups", "rds:DescribeDBEngineVersions",
    "rds:DescribeOrderableDBInstanceOptions", "rds:DescribeOptionGroups",
    "outposts:ListOutposts", "ssm:DescribeParameters", "rds:DescribeDBClusters",
    "ds:DescribeDirectories", "kms:ListAliases",
  ]);
});

test("the RDS Console metadata statement keeps its three reads with MFA in Seoul", () => {
  const policy = readPolicy("rds-console-read");
  assert.equal(policy.Statement.length, 2);
  assert.deepEqual(policy.Statement[0], {
    Sid: "ReadRdsConsoleMetadata",
    Effect: "Allow",
    Action: [
      "rds:DescribeCertificates",
      "rds:DescribeDBSecurityGroups",
      "rds:DescribeDBClusterParameterGroups",
    ],
    Resource: "*",
    Condition: {
      StringEquals: { "aws:RequestedRegion": "ap-northeast-2" },
      Bool: { "aws:MultiFactorAuthPresent": "true" },
    },
  });
});

test("the DB tag read grants only ListTagsForResource on the named DB with MFA in Seoul", () => {
  assert.deepEqual(readPolicy("rds-console-read").Statement[1], {
    Sid: "ReadLearningDbTags",
    Effect: "Allow",
    Action: ["rds:ListTagsForResource"],
    Resource: "arn:aws:rds:ap-northeast-2:<AWS_ACCOUNT_ID>:db:helpdesk-learning",
    Condition: {
      StringEquals: { "aws:RequestedRegion": "ap-northeast-2" },
      Bool: { "aws:MultiFactorAuthPresent": "true" },
    },
  });
});

test("Directory and KMS lookups do not grant content changes or bypass MFA and Region", () => {
  assert.deepEqual(allowed.flatMap((entry) => entry.Action)
    .filter((action) => action.startsWith("ds:") || action.startsWith("kms:")),
    ["ds:DescribeDirectories", "kms:ListAliases"]);
  for (const sid of ["RequireMfaExceptSelfEnrollment", "RestrictRegionalOperationsToSeoul"]) {
    const denial = statement(sid);
    assert.equal(denial.Effect, "Deny");
    assert.equal(denial.NotAction.some((action) =>
      ["rds:", "ds:", "kms:"].some((prefix) => action.startsWith(prefix))), false);
  }
});

test("only the three deployment service-linked roles can be created", () => {
  const rule = statement("CreateOnlyRequiredServiceLinkedRoles");
  assert.deepEqual(rule.Action, ["iam:CreateServiceLinkedRole"]);
  assert.equal(rule.Resource.length === 3 && rule.Resource.every((resource) =>
    /^arn:aws:iam::<AWS_ACCOUNT_ID>:role\/aws-service-role\/(ecs|rds|elasticloadbalancing)\.amazonaws\.com\/AWSServiceRoleFor/.test(resource)), true);
  assert.deepEqual(rule.Condition.StringEquals["iam:AWSServiceName"],
    ["ecs.amazonaws.com", "rds.amazonaws.com", "elasticloadbalancing.amazonaws.com"]);
});

test("new network resources must be tagged and cannot be placed into another project's VPC", () => {
  assert.equal(statement("CreateTaggedNetworkResources").Condition.StringEquals["aws:RequestTag/Project"]
    === "helpdesk-learning", true);
  const deny = statement("DenyChildrenInOtherVpcs");
  assert.equal(deny.Effect === "Deny" && deny.Resource.endsWith(":vpc/*")
    && deny.Condition.StringNotEquals["aws:ResourceTag/Project"] === "helpdesk-learning", true);
  assert.deepEqual(deny.Action, ["ec2:CreateSubnet", "ec2:CreateRouteTable", "ec2:CreateSecurityGroup"]);
  assert.equal(statement("ConfigureOnlyTaggedNetworkResources").Condition.StringEquals["aws:ResourceTag/Project"]
    === "helpdesk-learning", true);
});

test("network and ECS tags can be written only as part of the listed creation actions", () => {
  assert.equal(statement("TagNetworkOnlyAtCreation").Condition.StringEquals["ec2:CreateAction"].length === 7, true);
  assert.deepEqual(statement("TagEcsOnlyAtCreation").Condition.StringEquals["ecs:CreateAction"],
    ["CreateCluster", "CreateService", "RegisterTaskDefinition", "RunTask"]);
});

test("first task registration and run keep the measured size and named family", () => {
  for (const sid of ["RegisterTask", "RunTask"]) {
    const rule = statement(sid);
    assert.equal(rule.Resource === "arn:aws:ecs:ap-northeast-2:<AWS_ACCOUNT_ID>:task-definition/helpdesk-learning:*", true);
    assert.deepEqual(rule.Condition.NumericEquals, { "ecs:task-cpu": "256", "ecs:task-memory": "512" });
  }
  const registration = statement("RegisterTask");
  assert.deepEqual(registration.Condition["ForAllValues:StringEquals"]["ecs:compute-compatibility"], ["FARGATE"]);
  assert.equal(registration.Condition.Null["ecs:compute-compatibility"] === "false", true);
  assert.equal(statement("RunTask").Condition.ArnEquals["ecs:cluster"]
    === "arn:aws:ecs:ap-northeast-2:<AWS_ACCOUNT_ID>:cluster/helpdesk-learning", true);
});

test("RDS creation keeps the private encrypted Single-AZ PostgreSQL minimum", () => {
  const rule = statement("CreatePrivateDb");
  assert.equal(rule.Resource === "arn:aws:rds:ap-northeast-2:<AWS_ACCOUNT_ID>:db:helpdesk-learning", true);
  assert.equal(rule.Condition.StringEquals["rds:DatabaseEngine"] === "postgres"
    && rule.Condition.StringEquals["rds:DatabaseClass"] === "db.t4g.micro", true);
  assert.deepEqual(rule.Condition.NumericEquals, { "rds:StorageSize": "20" });
  assert.deepEqual(rule.Condition.Bool, {
    "rds:PubliclyAccessible": "false", "rds:MultiAz": "false", "rds:StorageEncrypted": "true",
  });
  assert.equal(rule.Condition.BoolIfExists["rds:ManageMasterUserPassword"] === "false", true);
  assert.equal(rule.Condition.StringEquals["aws:RequestTag/Project"] === "helpdesk-learning", true);
  assert.deepEqual(statement("UseDefaultGroups").Resource, [
    "arn:aws:rds:ap-northeast-2:<AWS_ACCOUNT_ID>:pg:default.postgres17",
    "arn:aws:rds:ap-northeast-2:<AWS_ACCOUNT_ID>:og:default:postgres-17",
  ]);
  assert.equal(allowed.flatMap((entry) => entry.Action).some((action) =>
    ["rds:CreateDBParameterGroup", "rds:ModifyDBParameterGroup"].includes(action)), false);
});

test("the Console can create only the named subnet group without a creation-time tag", () => {
  const rules = allowed.filter((entry) => entry.Action.includes("rds:CreateDBSubnetGroup"));
  assert.equal(rules.length, 1);
  assert.equal(rules[0].Sid, "CreateReadSubnet");
  assert.equal(rules[0].Resource,
    "arn:aws:rds:ap-northeast-2:<AWS_ACCOUNT_ID>:subgrp:helpdesk-learning");
  assert.equal(Object.hasOwn(rules[0], "Condition"), false);
});

test("every subnet group use permission requires the persisted Project tag", () => {
  const rules = allowed.filter((entry) => entry.Action.includes("rds:CreateDBInstance")
    && [entry.Resource].flat().some((resource) => resource.includes(":subgrp:")));
  assert.equal(rules.length, 1);
  assert.equal(rules[0].Resource,
    "arn:aws:rds:ap-northeast-2:<AWS_ACCOUNT_ID>:subgrp:helpdesk-learning");
  assert.deepEqual(rules[0].Condition.StringEquals, {
    "aws:RequestTag/Project": "helpdesk-learning",
    "rds:subgrp-tag/Project": "helpdesk-learning",
  });
});

test("RDS tag inspection is limited to the named DB and subnet group and tag writes keep Project", () => {
  const readers = allowed.filter((entry) => entry.Action.includes("rds:ListTagsForResource"));
  assert.equal(readers.length, 2);
  assert.deepEqual(readers.map((reader) => reader.Resource).sort(), [
    "arn:aws:rds:ap-northeast-2:<AWS_ACCOUNT_ID>:db:helpdesk-learning",
    "arn:aws:rds:ap-northeast-2:<AWS_ACCOUNT_ID>:subgrp:helpdesk-learning",
  ]);
  const writer = statement("TagNamedDb");
  assert.deepEqual(writer.Resource, [
    "arn:aws:rds:ap-northeast-2:<AWS_ACCOUNT_ID>:db:helpdesk-learning",
    "arn:aws:rds:ap-northeast-2:<AWS_ACCOUNT_ID>:subgrp:helpdesk-learning",
  ]);
  assert.equal(writer.Condition.StringEquals["aws:RequestTag/Project"], "helpdesk-learning");
});

test("ECS feature condition keys use their documented String operator, not Bool", () => {
  for (const sid of ["CreateTaggedService", "UpdateService", "RunTask"]) {
    const condition = statement(sid).Condition;
    assert.equal(Object.hasOwn(condition, "BoolIfExists"), false);
    assert.equal(condition.StringEqualsIfExists["ecs:enable-execute-command"] === "false"
      && condition.StringEqualsIfExists["ecs:enable-ebs-volumes"] === "false", true);
  }
});

test("certificate request is limited to the learning domain and non-exportable public DNS validation", () => {
  const rule = statement("RequestOnlyLearningPublicCertificate");
  assert.deepEqual(rule.Condition["ForAllValues:StringEquals"]["acm:DomainNames"], ["helpdesk.hmyoon.com"]);
  assert.equal(rule.Condition.StringEquals["acm:Export"] === "DISABLED"
    && rule.Condition.StringEquals["acm:ValidationMethod"] === "DNS"
    && rule.Condition.Null["acm:CertificateAuthority"] === "true"
    && rule.Condition.Null["acm:DomainNames"] === "false", true);
});

test("ACM search adds only one metadata action with explicit MFA and Seoul conditions", () => {
  const edge = readPolicy("edge");
  const searchRules = edge.Statement.filter((entry) => entry.Action.includes("acm:SearchCertificates"));
  assert.equal(searchRules.length, 1);
  assert.deepEqual(searchRules[0], {
    Sid: "ReadSeoulCertificateSearch",
    Effect: "Allow",
    Action: ["acm:SearchCertificates"],
    Resource: "*",
    Condition: {
      StringEquals: { "aws:RequestedRegion": "ap-northeast-2" },
      Bool: { "aws:MultiFactorAuthPresent": "true" },
    },
  });
  assert.equal(edge.Statement.flatMap((entry) => entry.Action)
    .some((action) => action.startsWith("acm-pca:")), false);
});

test("ACM tagging allows only the learning Project with MFA in Seoul and no time limit", () => {
  const edge = readPolicy("edge");
  const tagRules = edge.Statement.filter((entry) => entry.Action.includes("acm:AddTagsToCertificate"));
  assert.equal(tagRules.length, 1);
  assert.deepEqual(tagRules[0], {
    Sid: "TagOnlyLearningCertificateProject",
    Effect: "Allow",
    Action: ["acm:AddTagsToCertificate"],
    Resource: "arn:aws:acm:ap-northeast-2:<AWS_ACCOUNT_ID>:certificate/*",
    Condition: {
      StringEquals: {
        "aws:RequestedRegion": "ap-northeast-2",
        "aws:RequestTag/Project": "helpdesk-learning",
      },
      StringEqualsIfExists: { "aws:ResourceTag/Project": "helpdesk-learning" },
      "ForAllValues:StringEquals": { "aws:TagKeys": ["Project"] },
      Null: { "aws:TagKeys": "false" },
      Bool: { "aws:MultiFactorAuthPresent": "true" },
    },
  });
});

test("ALB security group lookup reads only the selected learning VPC with MFA in Seoul", () => {
  const edge = readPolicy("edge");
  const readers = edge.Statement.filter((entry) =>
    entry.Action.some((action) => action.startsWith("ec2:")));
  assert.deepEqual(readers, [{
    Sid: "ReadOnlyLearningVpcSecurityGroups",
    Effect: "Allow",
    Action: ["ec2:GetSecurityGroupsForVpc"],
    Resource: "arn:aws:ec2:ap-northeast-2:<AWS_ACCOUNT_ID>:vpc/<LEARNING_VPC_ID>",
    Condition: {
      StringEquals: {
        "aws:RequestedRegion": "ap-northeast-2",
        "aws:ResourceTag/Project": "helpdesk-learning",
      },
      Bool: { "aws:MultiFactorAuthPresent": "true" },
    },
  }]);
});

test("deployer reads only the named ECR repository and Helpdesk log data", () => {
  assert.equal(statement("ReadHelpdeskImageMetadata").Resource ===
    "arn:aws:ecr:ap-northeast-2:<AWS_ACCOUNT_ID>:repository/ai-helpdesk-learning-lab", true);
  assert.equal(statement("ReadOnlyHelpdeskLogGroup").Resource ===
    "arn:aws:logs:ap-northeast-2:<AWS_ACCOUNT_ID>:log-group:/ecs/helpdesk-learning:*", true);
  assert.equal(statement("ReadOnlyHelpdeskLogStreams").Resource ===
    "arn:aws:logs:ap-northeast-2:<AWS_ACCOUNT_ID>:log-group:/ecs/helpdesk-learning:log-stream:*", true);
});

test("CloudWatch access adds only GetMetricData with explicit MFA and Seoul conditions", () => {
  const edge = readPolicy("edge");
  const metricReaders = edge.Statement.filter((entry) =>
    entry.Action.some((action) => action.startsWith("cloudwatch:")));
  assert.deepEqual(metricReaders, [{
    Sid: "ReadSeoulMetricData",
    Effect: "Allow",
    Action: ["cloudwatch:GetMetricData"],
    Resource: "*",
    Condition: {
      StringEquals: { "aws:RequestedRegion": "ap-northeast-2" },
      Bool: { "aws:MultiFactorAuthPresent": "true" },
    },
  }]);
  assert.deepEqual(allowed.flatMap((entry) => entry.Action)
    .filter((action) => action.startsWith("cloudwatch:")), ["cloudwatch:GetMetricData"]);
  assert.equal(readPolicy("foundation").Statement.some((entry) =>
    Array.isArray(entry.Action) && entry.Action.some((action) => action.startsWith("cloudwatch:"))), false);
  assert.equal(readPolicy("runtime").Statement.some((entry) =>
    entry.Action.some((action) => action.startsWith("cloudwatch:"))), false);
});

test("ALB listener protocols use a set qualifier and require a value at creation", () => {
  for (const sid of ["CreateOnlyLearningListeners", "ModifyOnlyLearningListeners"]) {
    const condition = statement(sid).Condition;
    assert.deepEqual(condition["ForAllValues:StringEquals"]["elasticloadbalancing:ListenerProtocol"],
      ["HTTP", "HTTPS"]);
  }
  assert.equal(statement("CreateOnlyLearningListeners").Condition.Null["elasticloadbalancing:ListenerProtocol"]
    === "false", true);
});

test("deployer has no direct secret retrieval, ECR push, arbitrary IAM change or paid network expansion", () => {
  const forbidden = new Set([
    "ssm:GetParameter", "ssm:GetParameters", "ssm:GetParametersByPath", "ssm:PutParameter",
    "secretsmanager:GetSecretValue", "kms:Decrypt", "ecr:PutImage", "ecr:GetAuthorizationToken",
    "iam:CreateUser", "iam:CreateRole", "iam:PutRolePolicy", "iam:AttachUserPolicy",
    "iam:CreatePolicy", "iam:CreatePolicyVersion", "ec2:RunInstances", "ec2:CreateNatGateway",
    "ec2:CreateVpcEndpoint", "ec2:AllocateAddress", "ecs:ExecuteCommand", "acm:ExportCertificate",
  ]);
  assert.equal(allowed.flatMap((entry) => entry.Action).some((action) => forbidden.has(action)), false);
});
