import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { URL } from "node:url";

const sql = readFileSync(new URL("../../../deploy/rds/recovery-verification.sql", import.meta.url), "utf8");
const executable = sql.replace(/^\s*--.*$/gm, "");

test("recovery query keeps a read-only consistent view with bounded execution", () => {
  assert.match(executable, /BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY;/);
  assert.match(executable, /SET LOCAL statement_timeout = '5s';/);
  assert.match(executable, /SET LOCAL lock_timeout = '2s';/);
  assert.match(executable, /SET LOCAL TIME ZONE 'UTC';/);
  assert.match(executable, /SET LOCAL DateStyle = 'ISO, YMD';/);
  assert.equal(executable.trim().endsWith("COMMIT;"), true);
});

test("query fingerprints all six business tables and the migration history without raw output", () => {
  const tables = [...executable.matchAll(/FROM public\.([a-z_]+) AS row_data/g)].map((match) => match[1]);
  assert.deepEqual(tables, ["tickets", "ticket_messages", "ai_suggestion_jobs",
    "ai_suggestion_attempts", "ticket_suggestions", "ticket_suggestion_categories", "flyway_schema_history"]);
  assert.equal((executable.match(/encode\(sha256\(convert_to\(COALESCE\(/g) ?? []).length, 7);
  assert.equal((executable.match(/COLLATE "C"/g) ?? []).length, 7);
  assert.match(executable, /SELECT table_name, row_count, content_sha256/);
  assert.doesNotMatch(executable, /\bSELECT\s+(?:\w+\.)?(?:body|author_username|summary)\b/i);
});

test("verification text has no write, credential inspection or sequence advancement operation", () => {
  assert.doesNotMatch(executable, /^\s*(?:INSERT|UPDATE|DELETE|CREATE|ALTER|DROP|TRUNCATE|COPY|CALL|DO)\b/im);
  assert.doesNotMatch(executable, /\b(?:nextval|setval|dblink|pg_authid|pg_shadow|rolpassword|PGPASSWORD)\b/i);
  assert.match(executable, /AS orphan_messages/);
  assert.match(executable, /AS orphan_jobs/);
  assert.match(executable, /AS orphan_attempts/);
  assert.match(executable, /AS orphan_suggestions/);
  assert.match(executable, /AS orphan_categories/);
  assert.match(executable, /SELECT version, success/);
  assert.match(executable, /SELECT 'tickets_id_seq' AS sequence_name, last_value, is_called/);
});
