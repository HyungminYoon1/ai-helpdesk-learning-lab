import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { URL } from "node:url";

const policy = JSON.parse(readFileSync(new URL(
  "../../../deploy/iam/deployer-db-recovery-policy.template.json", import.meta.url,
), "utf8"));
const statements = policy.Statement;
const statement = (sid) => statements.find((entry) => entry.Sid === sid);
const prefix = "arn:aws:rds:ap-northeast-2:<AWS_ACCOUNT_ID>:";
const source = `${prefix}db:helpdesk-learning`;
const snapshot = `${prefix}snapshot:helpdesk-learning-20261016-recovery`;
const restored = `${prefix}db:helpdesk-learning-restore-20261016`;
const latestSnapshot = `${prefix}snapshot:helpdesk-learning-20261021-final`;
const latestRestored = `${prefix}db:helpdesk-learning-restore-20261021`;
const snapshots = [snapshot, latestSnapshot];
const restoredDbs = [restored, latestRestored];
const rulesFor = (action) => statements.filter((entry) => entry.Action.includes(action));

test("recovery template is valid JSON within one managed policy size limit", () => {
  assert.equal(policy.Version, "2012-10-17");
  assert.equal(JSON.stringify(policy).replaceAll("<AWS_ACCOUNT_ID>", "000000000000").length <= 6144, true);
  assert.equal(new Set(statements.map((entry) => entry.Sid)).size, statements.length);
  assert.equal(statements.every((entry) => entry.Effect === "Allow"
    && !Object.hasOwn(entry, "Principal") && !Object.hasOwn(entry, "NotResource")), true);
});

test("every recovery permission explicitly requires MFA and Seoul", () => {
  for (const entry of statements) {
    assert.equal(entry.Condition.StringEquals["aws:RequestedRegion"], "ap-northeast-2");
    assert.equal(entry.Condition.Bool["aws:MultiFactorAuthPresent"], "true");
    assert.equal([entry.Resource].flat().every((resource) => resource.startsWith(prefix)), true);
  }
});

test("recovery template adds only the eight named RDS actions with no wildcard actions", () => {
  assert.deepEqual([...new Set(statements.flatMap((entry) => entry.Action))].sort(), [
    "rds:AddTagsToResource", "rds:CreateDBSnapshot", "rds:DeleteDBInstance",
    "rds:DescribeDBSnapshotAttributes", "rds:DescribeDBSnapshots", "rds:ListTagsForResource",
    "rds:RestoreDBInstanceFromDBSnapshot", "rds:StopDBInstance",
  ].sort());
});

test("only snapshot list metadata covers other snapshots in the same account and Seoul", () => {
  const reader = statement("ReadSnapshotList");
  assert.deepEqual(reader.Action, ["rds:DescribeDBSnapshots"]);
  assert.deepEqual(reader.Resource, [`${prefix}snapshot:*`, source, ...restoredDbs]);
  assert.deepEqual(statements.filter((entry) => [entry.Resource].flat()
    .some((resource) => resource.includes("*"))), [reader]);
});

test("snapshot creation keeps the original source and only the two approved snapshot names", () => {
  const creators = rulesFor("rds:CreateDBSnapshot");
  assert.deepEqual(creators.flatMap((entry) => [entry.Resource].flat()), [source, ...snapshots]);
  assert.equal(statement("SnapshotOnlySource").Condition.StringEquals["aws:ResourceTag/Project"],
    "helpdesk-learning");
  assert.equal(creators.every((entry) => entry.Condition.StringEquals["aws:RequestTag/Project"]
    === "helpdesk-learning"), true);
});

test("restore target keeps private Single-AZ minimum after only three approved condition removals", () => {
  const target = statement("RestoreOnlyVerificationDb");
  assert.deepEqual(target.Resource, restoredDbs);
  assert.deepEqual(target.Action, ["rds:RestoreDBInstanceFromDBSnapshot"]);
  assert.deepEqual(target.Condition.StringEquals, {
    "aws:RequestedRegion": "ap-northeast-2",
    "aws:RequestTag/Project": "helpdesk-learning",
    "rds:DatabaseClass": "db.t4g.micro",
  });
  assert.deepEqual(target.Condition.NumericEquals, { "rds:StorageSize": "20" });
  assert.deepEqual(target.Condition.Bool, {
    "aws:MultiFactorAuthPresent": "true", "rds:PubliclyAccessible": "false",
    "rds:MultiAz": "false",
  });
  assert.deepEqual(target.Condition.BoolIfExists, { "rds:ManageMasterUserPassword": "false" });
});

test("restore uses only the selected snapshot, subnet group and PostgreSQL 17 defaults", () => {
  const restorers = rulesFor("rds:RestoreDBInstanceFromDBSnapshot");
  assert.deepEqual(restorers.flatMap((entry) => [entry.Resource].flat()).sort(), [
    ...restoredDbs, ...snapshots, `${prefix}subgrp:helpdesk-learning`,
    `${prefix}pg:default.postgres17`, `${prefix}og:default:postgres-17`,
  ].sort());
  assert.equal(restorers.every((entry) => entry.Condition.StringEquals["aws:RequestTag/Project"]
    === "helpdesk-learning"), true);
  assert.equal(statement("RestoreOnlyRecoverySnapshot").Condition.StringEquals["aws:ResourceTag/Project"],
    "helpdesk-learning");
  assert.equal(statement("RestoreWithLearningSubnet").Condition.StringEquals["rds:subgrp-tag/Project"],
    "helpdesk-learning");
});

test("sharing inspection and tag access do not add snapshot sharing or modification", () => {
  assert.deepEqual(statement("ReadSnapshotSharing").Resource, snapshots);
  assert.deepEqual(statement("ReadRecoveryTags").Resource,
    [snapshot, restored, latestSnapshot, latestRestored]);
  const tagger = statement("TagOnlyRecoveryObjects");
  assert.deepEqual(tagger.Resource, [snapshot, restored, latestSnapshot, latestRestored]);
  assert.equal(tagger.Condition.StringEquals["aws:RequestTag/Project"], "helpdesk-learning");
  assert.deepEqual(tagger.Condition["ForAllValues:StringEquals"]["aws:TagKeys"], ["Project"]);
});

test("stop and deletion target only the two explicitly approved temporary DBs", () => {
  for (const action of ["rds:StopDBInstance", "rds:DeleteDBInstance"]) {
    const rules = rulesFor(action);
    assert.equal(rules.length, 1);
    assert.deepEqual([rules[0].Resource].flat(), restoredDbs);
    assert.equal(rules[0].Condition.StringEquals["aws:ResourceTag/Project"], "helpdesk-learning");
  }
  assert.equal(statements.flatMap((entry) => entry.Action).includes("rds:DeleteDBSnapshot"), false);
});

test("closeout permissions add no wildcard or original DB mutation beyond snapshot creation", () => {
  assert.equal(statements.length, 12);
  for (const entry of statements) {
    if (entry.Sid === "ReadSnapshotList") continue;
    assert.equal([entry.Resource].flat().some((resource) => resource.includes("*")), false);
    if ([entry.Resource].flat().includes(source)) {
      assert.equal(entry.Sid, "SnapshotOnlySource");
      assert.deepEqual(entry.Action, ["rds:CreateDBSnapshot"]);
    }
  }
  assert.equal(rulesFor("rds:DeleteDBInstance").some((entry) =>
    [entry.Resource].flat().includes(latestRestored)), true);
});

test("approved cleanup preserves the exact delete action, tag, MFA and Seoul conditions", () => {
  const cleanup = statement("DeleteOnlyVerificationDb");
  assert.deepEqual(cleanup.Action, ["rds:DeleteDBInstance"]);
  assert.deepEqual(cleanup.Resource, restoredDbs);
  assert.deepEqual(cleanup.Condition, {
    StringEquals: {
      "aws:RequestedRegion": "ap-northeast-2",
      "aws:ResourceTag/Project": "helpdesk-learning",
    },
    Bool: { "aws:MultiFactorAuthPresent": "true" },
  });
  assert.equal([source, ...snapshots].some((resource) =>
    cleanup.Resource.includes(resource)), false);
});
