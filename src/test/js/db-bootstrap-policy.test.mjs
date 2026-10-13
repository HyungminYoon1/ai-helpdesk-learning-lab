import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { URL } from "node:url";

const policy = JSON.parse(readFileSync(new URL(
  "../../../deploy/iam/deployer-db-bootstrap-policy.template.json", import.meta.url,
), "utf8"));
const rule = (sid) => policy.Statement.find((entry) => entry.Sid === sid);
const allowed = policy.Statement.filter((entry) => entry.Effect === "Allow");
const sessionSid = "UseOnlyConfirmedBootstrapEnvironment";
const creationPolicy = {
  ...policy,
  Statement: policy.Statement.filter((entry) => entry.Sid !== sessionSid),
};
const renderForSize = (source) => JSON.stringify(source)
  .replaceAll("<AWS_ACCOUNT_ID>", "000000000000")
  .replaceAll("<LEARNING_VPC_ID>", "vpc-00000000000000000")
  .replaceAll("<BOOTSTRAP_SUBNET_ID>", "subnet-00000000000000000")
  .replaceAll("<BOOTSTRAP_SECURITY_GROUP_ID>", "sg-00000000000000000")
  .replaceAll("<BOOTSTRAP_ENVIRONMENT_ID>", "00000000-0000-0000-0000-000000000000");

test("bootstrap creation and exact-environment policies fit the managed policy limit", () => {
  for (const candidate of [creationPolicy, policy]) {
    assert.equal(candidate.Version, "2012-10-17");
    assert.equal(renderForSize(candidate).length <= 6144, true);
  }
  assert.equal(new Set(policy.Statement.map((entry) => entry.Sid)).size, policy.Statement.length);
  assert.equal(policy.Statement.every((entry) => !Object.hasOwn(entry, "Principal")), true);
});

test("bootstrap grants stay in Seoul and contain no service-wide allow wildcard", () => {
  assert.equal(allowed.every((entry) => entry.Condition.StringEquals["aws:RequestedRegion"]
    === "ap-northeast-2" && entry.Action.every((action) => !action.includes("*"))), true);
});

test("direct bootstrap calls require MFA without blocking forwarded service calls", () => {
  const mfa = rule("RequireMfaForBootstrapDirectCalls");
  assert.equal(mfa.Effect, "Deny");
  assert.deepEqual(mfa.Condition.BoolIfExists, {
    "aws:MultiFactorAuthPresent": "false", "aws:ViaAWSService": "false",
  });
});

test("environment creation requires the selected VPC, private subnet and bootstrap group", () => {
  const creation = rule("CreateOnlyPrivateBootstrapEnvironment");
  assert.deepEqual(creation.Action, ["cloudshell:CreateEnvironment"]);
  assert.deepEqual(creation.Condition["ForAllValues:StringEquals"], {
    "cloudshell:VpcIds": ["<LEARNING_VPC_ID>"],
    "cloudshell:SubnetIds": ["<BOOTSTRAP_SUBNET_ID>"],
    "cloudshell:SecurityGroupIds": ["<BOOTSTRAP_SECURITY_GROUP_ID>"],
  });
  assert.deepEqual(creation.Condition.Null, {
    "cloudshell:VpcIds": "false", "cloudshell:SubnetIds": "false",
    "cloudshell:SecurityGroupIds": "false",
  });
});

test("the first application stage grants no terminal session before its environment ID is known", () => {
  const creationAllowed = creationPolicy.Statement.filter((entry) => entry.Effect === "Allow");
  assert.equal(creationAllowed.flatMap((entry) => entry.Action).some((action) =>
    ["cloudshell:CreateSession", "cloudshell:StartEnvironment", "cloudshell:DeleteEnvironment"].includes(action)), false);
});

test("terminal use and cleanup reference one exact environment instead of environment wildcard", () => {
  const session = rule(sessionSid);
  assert.equal(session.Resource,
    "arn:aws:cloudshell:ap-northeast-2:<AWS_ACCOUNT_ID>:environment/<BOOTSTRAP_ENVIRONMENT_ID>");
  assert.equal(session.Resource.includes("*"), false);
  assert.deepEqual(session.Action, [
    "cloudshell:GetEnvironmentStatus", "cloudshell:StartEnvironment", "cloudshell:CreateSession",
    "cloudshell:StopEnvironment", "cloudshell:DeleteEnvironment",
  ]);
});

test("the shell cannot receive AWS console credentials or generate file transfer URLs", () => {
  const deny = rule("DenyBootstrapCredentialForwardingAndFileUrls");
  assert.equal(deny.Effect, "Deny");
  assert.deepEqual(deny.Action, [
    "cloudshell:PutCredentials", "cloudshell:GetFileUploadUrls", "cloudshell:GetFileDownloadUrls",
  ]);
  assert.equal(allowed.flatMap((entry) => entry.Action).includes("cloudshell:PutCredentials"), false);
});

test("interface creation references only the exact private subnet and bootstrap group", () => {
  const creation = rule("CreateInterfaceUsingOnlyBootstrapSubnetAndGroup");
  assert.deepEqual(creation.Resource, [
    "arn:aws:ec2:ap-northeast-2:<AWS_ACCOUNT_ID>:subnet/<BOOTSTRAP_SUBNET_ID>",
    "arn:aws:ec2:ap-northeast-2:<AWS_ACCOUNT_ID>:security-group/<BOOTSTRAP_SECURITY_GROUP_ID>",
  ]);
  assert.equal(creation.Condition.ArnEquals["ec2:Vpc"],
    "arn:aws:ec2:ap-northeast-2:<AWS_ACCOUNT_ID>:vpc/<LEARNING_VPC_ID>");
});

test("new interfaces must have the CloudShell tag and cannot be arbitrarily retagged", () => {
  for (const sid of ["CreateOnlyCloudShellTaggedInterface", "TagBootstrapInterfaceOnlyAtCreation"]) {
    const entry = rule(sid);
    assert.equal(entry.Condition.StringEquals["aws:RequestTag/ManagedByCloudShell"], "");
    assert.deepEqual(entry.Condition["ForAllValues:StringEquals"]["aws:TagKeys"], ["ManagedByCloudShell"]);
    assert.equal(entry.Condition.Null["aws:TagKeys"], "false");
    assert.equal(entry.Condition.Null["aws:RequestTag/ManagedByCloudShell"], "false");
  }
  assert.equal(rule("TagBootstrapInterfaceOnlyAtCreation").Condition.StringEquals["ec2:CreateAction"],
    "CreateNetworkInterface");
  // ec2:Subnet/ec2:Vpc are not supported on the new interface resource in CreateNetworkInterface.
  assert.equal(Object.hasOwn(rule("CreateOnlyCloudShellTaggedInterface").Condition, "ArnEquals"), false);
});

test("interface delegation and deletion require the bootstrap VPC, subnet and CloudShell tag", () => {
  for (const sid of ["GrantAttachOnlyForBootstrapInterface", "CleanUpOnlyBootstrapInterface"]) {
    const entry = rule(sid);
    assert.equal(entry.Condition.StringEquals["aws:ResourceTag/ManagedByCloudShell"], "");
    assert.deepEqual(entry.Condition.ArnEquals, {
      "ec2:Vpc": "arn:aws:ec2:ap-northeast-2:<AWS_ACCOUNT_ID>:vpc/<LEARNING_VPC_ID>",
      "ec2:Subnet": "arn:aws:ec2:ap-northeast-2:<AWS_ACCOUNT_ID>:subnet/<BOOTSTRAP_SUBNET_ID>",
    });
  }
  assert.equal(rule("GrantAttachOnlyForBootstrapInterface").Condition.StringEquals["ec2:Permission"],
    "INSTANCE-ATTACH");
});

test("only the named temporary group can be deleted; the three service groups stay excluded", () => {
  const cleanup = rule("CleanUpOnlyNamedBootstrapGroup");
  assert.equal(cleanup.Resource,
    "arn:aws:ec2:ap-northeast-2:<AWS_ACCOUNT_ID>:security-group/<BOOTSTRAP_SECURITY_GROUP_ID>");
  assert.equal(cleanup.Condition.StringEquals["aws:ResourceTag/Project"], "helpdesk-learning");
  assert.equal(cleanup.Condition.StringEquals["aws:ResourceTag/Purpose"], "db-bootstrap");
  assert.equal(allowed.filter((entry) => entry.Action.includes("ec2:DeleteSecurityGroup")).length, 1);
});

test("bootstrap adds no secret reads, IAM changes, runtime deployment or paid network services", () => {
  const actions = allowed.flatMap((entry) => entry.Action);
  assert.equal(actions.some((action) => /^(ssm|secretsmanager|kms|iam|ecs|rds|ecr|elasticloadbalancing):/.test(action)), false);
  assert.equal(actions.some((action) => [
    "ec2:RunInstances", "ec2:CreateNatGateway", "ec2:CreateVpcEndpoint", "ec2:AllocateAddress",
  ].includes(action)), false);
  assert.equal(actions.filter((action) => action.startsWith("ec2:Delete")).length, 2);
});
