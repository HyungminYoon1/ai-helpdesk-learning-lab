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
    throw new Error("DEPLOYER_CLOSEOUT_POLICY_PARSE_FAILED");
  }
};
const policy = readPolicy("closeout");
const foundation = readPolicy("foundation");
const rules = policy.Statement;
const statement = (sid) => rules.find((rule) => rule.Sid === sid);
const zone = "arn:aws:route53:::hostedzone/<DNS_LAB_ZONE_ID>";
const regionalConditions = {
  StringEquals: {
    "aws:RequestedRegion": "ap-northeast-2",
    "aws:ResourceTag/Project": "helpdesk-learning",
  },
  Bool: { "aws:MultiFactorAuthPresent": "true" },
};
const dnsConditions = {
  StringEquals: { "aws:RequestedRegion": "us-east-1" },
  Bool: { "aws:MultiFactorAuthPresent": "true" },
};
const existingDnsExceptions = [
  "route53:GetHostedZoneCount", "route53:ListHostedZones", "route53:ListHostedZonesByName",
  "route53:GetHostedZone", "route53:ListResourceRecordSets",
  "route53:ChangeResourceRecordSets", "route53:GetChange",
];
const closeoutDnsExceptions = ["route53:GetDNSSEC", "route53:DeleteHostedZone"];

test("closeout is a separate six-statement managed policy within the size limit", () => {
  assert.equal(policy.Version, "2012-10-17");
  assert.equal(rules.length, 6);
  assert.equal(new Set(rules.map((rule) => rule.Sid)).size, 6);
  const rendered = JSON.stringify(policy)
    .replaceAll("<AWS_ACCOUNT_ID>", "000000000000")
    .replaceAll("<LEARNING_ALB_ID>", "0000000000000000")
    .replaceAll("<DNS_LAB_ZONE_ID>", "Z0123456789ABCDEF");
  assert.ok(rendered.length <= 6144);
  for (const rule of rules) {
    assert.equal(rule.Effect, "Allow");
    assert.equal(typeof rule.Resource, "string");
    assert.equal(rule.Resource.includes("*"), false);
    assert.equal(rule.Action.some((action) => action.includes("*")), false);
    for (const excluded of ["Principal", "NotResource", "NotAction"]) {
      assert.equal(Object.hasOwn(rule, excluded), false);
    }
  }
});

test("closeout grants exactly five actions and no snapshot secret log or IAM change", () => {
  assert.deepEqual([...new Set(rules.flatMap((rule) => rule.Action))].sort(), [
    "rds:DeleteDBInstance", "elasticloadbalancing:DeleteLoadBalancer",
    "route53:GetDNSSEC", "route53:DeleteHostedZone", "route53:ChangeResourceRecordSets",
  ].sort());
});

test("source deletion is confined to one exact DB ARN with Project MFA and Seoul", () => {
  assert.deepEqual(statement("DeleteOnlySourceDb"), {
    Sid: "DeleteOnlySourceDb", Effect: "Allow", Action: ["rds:DeleteDBInstance"],
    Resource: "arn:aws:rds:ap-northeast-2:<AWS_ACCOUNT_ID>:db:helpdesk-learning",
    Condition: regionalConditions,
  });
});

test("ALB deletion pins the existing ARN suffix rather than a name wildcard", () => {
  assert.deepEqual(statement("DeleteOnlyLearningAlb"), {
    Sid: "DeleteOnlyLearningAlb", Effect: "Allow",
    Action: ["elasticloadbalancing:DeleteLoadBalancer"],
    Resource: "arn:aws:elasticloadbalancing:ap-northeast-2:<AWS_ACCOUNT_ID>:loadbalancer/app/helpdesk-learning/<LEARNING_ALB_ID>",
    Condition: regionalConditions,
  });
});

test("DNSSEC inspection does not permit signing changes or other zones", () => {
  assert.deepEqual(statement("ReadOnlyDnsLabDnssec"), {
    Sid: "ReadOnlyDnsLabDnssec", Effect: "Allow", Action: ["route53:GetDNSSEC"],
    Resource: zone, Condition: dnsConditions,
  });
});

test("zone deletion uses the exact lab zone with explicit MFA and global endpoint", () => {
  assert.deepEqual(statement("DeleteOnlyDnsLabZone"), {
    Sid: "DeleteOnlyDnsLabZone", Effect: "Allow", Action: ["route53:DeleteHostedZone"],
    Resource: zone, Condition: dnsConditions,
  });
});

test("record deletion keeps the name-type pairs separate and rejects absent condition keys", () => {
  const writers = rules.filter((rule) => rule.Action.includes("route53:ChangeResourceRecordSets"));
  assert.equal(writers.length, 2);
  for (const [index, [name, type]] of [
    ["proof.dns-lab.hmyoon.com", "TXT"], ["dns-lab.hmyoon.com", "A"],
  ].entries()) {
    assert.equal(writers[index].Resource, zone);
    assert.deepEqual(writers[index].Action, ["route53:ChangeResourceRecordSets"]);
    assert.deepEqual(writers[index].Condition, {
      ...dnsConditions,
      "ForAllValues:StringEquals": {
        "route53:ChangeResourceRecordSetsNormalizedRecordNames": [name],
        "route53:ChangeResourceRecordSetsRecordTypes": [type],
        "route53:ChangeResourceRecordSetsActions": ["DELETE"],
      },
      Null: {
        "route53:ChangeResourceRecordSetsNormalizedRecordNames": "false",
        "route53:ChangeResourceRecordSetsRecordTypes": "false",
        "route53:ChangeResourceRecordSetsActions": "false",
      },
    });
  }
});

test("Foundation adds only the two closeout DNS exceptions and no Allow", () => {
  const regional = foundation.Statement.find((rule) => rule.Sid === "RestrictRegionalOperationsToSeoul");
  assert.deepEqual(regional, {
    Sid: "RestrictRegionalOperationsToSeoul", Effect: "Deny",
    NotAction: [
      "iam:*", "sts:GetCallerIdentity", "sts:GetSessionToken",
      ...existingDnsExceptions, ...closeoutDnsExceptions,
    ],
    Resource: "*", Condition: { StringNotEquals: { "aws:RequestedRegion": "ap-northeast-2" } },
  });
  assert.equal(foundation.Statement.some((rule) => rule.Effect === "Allow"
    && rule.Action.some((action) => action.startsWith("route53:"))), false);
});
