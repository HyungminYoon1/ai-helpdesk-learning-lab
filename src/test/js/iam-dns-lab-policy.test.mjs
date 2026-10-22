import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { URL } from "node:url";

const readPolicy = (name) => JSON.parse(readFileSync(new URL(
  `../../../deploy/iam/deployer-${name}-policy.template.json`, import.meta.url,
), "utf8"));
const bootstrap = readPolicy("dns-lab-bootstrap");
const records = readPolicy("dns-lab-records");
const foundation = readPolicy("foundation");
const metadataActions = [
  "route53:GetHostedZoneCount", "route53:ListHostedZones", "route53:ListHostedZonesByName",
];
const finalExceptions = [
  ...metadataActions, "route53:GetHostedZone", "route53:ListResourceRecordSets",
  "route53:ChangeResourceRecordSets", "route53:GetChange",
];
const closeoutExceptions = ["route53:GetDNSSEC", "route53:DeleteHostedZone"];
const zoneArn = "arn:aws:route53:::hostedzone/<DNS_LAB_ZONE_ID>";
const regionAndMfa = {
  StringEquals: { "aws:RequestedRegion": "us-east-1" },
  Bool: { "aws:MultiFactorAuthPresent": "true" },
};

test("DNS policies fit the managed policy size limit with no principals or wildcard actions", () => {
  for (const policy of [bootstrap, records]) {
    assert.equal(policy.Version, "2012-10-17");
    assert.ok(JSON.stringify(policy).replaceAll("<DNS_LAB_ZONE_ID>", "Z0123456789ABCDEF").length <= 6144);
    assert.equal(new Set(policy.Statement.map((rule) => rule.Sid)).size, policy.Statement.length);
    for (const rule of policy.Statement) {
      assert.equal(rule.Effect, "Allow");
      assert.equal(Object.hasOwn(rule, "Principal"), false);
      assert.equal(Object.hasOwn(rule, "NotResource"), false);
      assert.ok(rule.Action.every((action) => action.startsWith("route53:") && !action.includes("*")));
      assert.deepEqual(rule.Condition.Bool, regionAndMfa.Bool);
      assert.deepEqual(rule.Condition.StringEquals, regionAndMfa.StringEquals);
    }
  }
});

test("bootstrap grants only account metadata, public zone creation and change status", () => {
  assert.deepEqual(bootstrap.Statement.map((rule) => rule.Sid), [
    "ReadDnsLabZoneList", "CreatePublicDnsLabZoneTemporarily", "ReadDnsLabChangeStatus",
  ]);
  assert.deepEqual(bootstrap.Statement[0], {
    Sid: "ReadDnsLabZoneList", Effect: "Allow", Action: metadataActions, Resource: "*",
    Condition: regionAndMfa,
  });
  assert.deepEqual(bootstrap.Statement[1], {
    Sid: "CreatePublicDnsLabZoneTemporarily", Effect: "Allow",
    Action: ["route53:CreateHostedZone"], Resource: "*",
    Condition: { ...regionAndMfa, Null: { "route53:VPCs": "true" } },
  });
  assert.deepEqual(bootstrap.Statement[2], {
    Sid: "ReadDnsLabChangeStatus", Effect: "Allow", Action: ["route53:GetChange"],
    Resource: "arn:aws:route53:::change/*", Condition: regionAndMfa,
  });
});

test("Foundation keeps seven DNS record exceptions and adds only two closeout exceptions", () => {
  const regional = foundation.Statement.find((rule) => rule.Sid === "RestrictRegionalOperationsToSeoul");
  assert.deepEqual(regional, {
    Sid: "RestrictRegionalOperationsToSeoul", Effect: "Deny",
    NotAction: [
      "iam:*", "sts:GetCallerIdentity", "sts:GetSessionToken",
      ...finalExceptions, ...closeoutExceptions,
    ],
    Resource: "*", Condition: { StringNotEquals: { "aws:RequestedRegion": "ap-northeast-2" } },
  });
  assert.ok(!regional.NotAction.includes("route53:CreateHostedZone"));
  assert.equal(foundation.Statement.some((rule) => rule.Effect === "Allow"
    && rule.Action.some((action) => action.startsWith("route53:"))), false);
});

test("DNS exceptions do not change the existing MFA denial or its enrollment exceptions", () => {
  assert.deepEqual(foundation.Statement.find((rule) => rule.Sid === "RequireMfaExceptSelfEnrollment"), {
    Sid: "RequireMfaExceptSelfEnrollment", Effect: "Deny",
    NotAction: [
      "iam:CreateVirtualMFADevice", "iam:EnableMFADevice", "iam:ResyncMFADevice",
      "iam:ListMFADevices", "iam:ListVirtualMFADevices", "iam:GetUser",
      "iam:GetAccountPasswordPolicy", "sts:GetSessionToken",
    ],
    Resource: "*",
    Condition: { BoolIfExists: { "aws:MultiFactorAuthPresent": "false", "aws:ViaAWSService": "false" } },
  });
});

test("final zone reads and record writes use the exact future zone ARN", () => {
  const zoneRules = records.Statement.filter((rule) => rule.Action.some((action) =>
    ["route53:GetHostedZone", "route53:ListResourceRecordSets", "route53:ChangeResourceRecordSets"]
      .includes(action)));
  assert.equal(zoneRules.length, 3);
  assert.ok(zoneRules.every((rule) => rule.Resource === zoneArn));
  assert.deepEqual(zoneRules[0], {
    Sid: "ReadOnlyDnsLabZone", Effect: "Allow",
    Action: ["route53:GetHostedZone", "route53:ListResourceRecordSets"],
    Resource: zoneArn, Condition: regionAndMfa,
  });
});

test("the final policy creates only the one TXT name and the one A name without UPSERT or DELETE", () => {
  const writers = records.Statement.filter((rule) => rule.Action.includes("route53:ChangeResourceRecordSets"));
  assert.equal(writers.length, 2);
  for (const [index, [name, type]] of [
    ["proof.dns-lab.hmyoon.com", "TXT"], ["dns-lab.hmyoon.com", "A"],
  ].entries()) {
    assert.deepEqual(writers[index].Action, ["route53:ChangeResourceRecordSets"]);
    assert.deepEqual(writers[index].Condition, {
      ...regionAndMfa,
      "ForAllValues:StringEquals": {
        "route53:ChangeResourceRecordSetsNormalizedRecordNames": [name],
        "route53:ChangeResourceRecordSetsRecordTypes": [type],
        "route53:ChangeResourceRecordSetsActions": ["CREATE"],
      },
      Null: {
        "route53:ChangeResourceRecordSetsNormalizedRecordNames": "false",
        "route53:ChangeResourceRecordSetsRecordTypes": "false",
        "route53:ChangeResourceRecordSetsActions": "false",
      },
    });
  }
});

test("the final action allowlist excludes zone creation, deletion, tagging and private VPC attachment", () => {
  assert.deepEqual([...new Set(records.Statement.flatMap((rule) => rule.Action))].sort(),
    [...finalExceptions].sort());
  assert.deepEqual(records.Statement.find((rule) => rule.Sid === "ReadDnsLabZoneList").Action,
    metadataActions);
  assert.deepEqual(records.Statement.find((rule) => rule.Sid === "ReadDnsLabChangeStatus"), {
    Sid: "ReadDnsLabChangeStatus", Effect: "Allow", Action: ["route53:GetChange"],
    Resource: "arn:aws:route53:::change/*", Condition: regionAndMfa,
  });
});
