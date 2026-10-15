import assert from "node:assert/strict";
import { Buffer } from "node:buffer";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { delimiter, dirname, join, resolve } from "node:path";
import test from "node:test";
import { fileURLToPath, URL } from "node:url";
import { gunzipSync } from "node:zlib";
import { buildCloudShellRdsConnectCommand, parseConnectArguments } from "../../../scripts/cloudshell-rds-connect.mjs";

const root = fileURLToPath(new URL("../../../", import.meta.url));
const read = path => readFileSync(join(root, path), "utf8");
const endpoint = "helpdesk-learning.example123.ap-northeast-2.rds.amazonaws.com";
const assets = {
    bundle: readFileSync(join(root, "docker/rds/ap-northeast-2-bundle.pem")),
    manifest: JSON.parse(read("docker/rds/bundle-manifest.json"))
};
const generated = buildCloudShellRdsConnectCommand({ endpoint });
const wrapper = join(root, "scripts/New-CloudShellRdsConnectCommand.ps1");
const generator = join(root, "scripts/cloudshell-rds-connect.mjs");

// Child fixtures inherit executable/temp paths only, never real project credentials.
function environment(extra = {}) {
    const safe = { PATH: [dirname(process.execPath), process.env.PATH ?? process.env.Path ?? ""].join(delimiter) };
    for (const name of ["PATHEXT", "SystemRoot", "TEMP", "TMP"]) {
        if (process.env[name] !== undefined) safe[name] = process.env[name];
    }
    return { ...safe, ...extra };
}

function available(candidates) {
    return candidates.find(candidate => spawnSync(candidate, ["--version"], {
        env: environment(), encoding: "utf8", timeout: 10000, windowsHide: true
    }).status === 0);
}
const bash = available(process.platform === "win32"
    ? ["C:/Program Files/Git/bin/bash.exe", "bash.exe"] : ["bash"]);
const pwsh = available([process.platform === "win32" ? "pwsh.exe" : "pwsh"]);

function bashPath(value) {
    return value.replaceAll("\\", "/").replace(/^([A-Za-z]):/, (_, drive) => `/${drive.toLowerCase()}`);
}

function cli(args, extraEnvironment = {}) {
    return spawnSync(process.execPath, [generator, ...args], {
        cwd: root, env: environment(extraEnvironment), encoding: "utf8", timeout: 20000, windowsHide: true
    });
}

function rejectsCode(action, code) {
    assert.throws(action, error => error.message === code);
}

test("generated command embeds exactly the reviewed CA bytes with LF and a pinned checksum", () => {
    const encoded = generated.split("\nHELPDESK_PUBLIC_RDS_CA_GZIP_BASE64\n")[0]
        .split(/base64 --decode <<[^\n]+\n/)[1];
    const restored = gunzipSync(Buffer.from(encoded, "base64"));
    assert.equal(restored.equals(assets.bundle), true);
    assert.equal(generated.includes(assets.manifest.sha256), true);
    assert.equal(generated.includes("\r") || generated.charCodeAt(0) === 0xfeff, false);
    assert.equal(generated.startsWith("(\n") && generated.endsWith(")\n"), true);
});

test("command creates its own CA and uses verified TLS, explicit target and read-only psql", () => {
    for (const required of [
        "mktemp -d /tmp/helpdesk-rds-XXXXXXXXXX", "sha256sum --check --status",
        'chmod 0400 "$helpdesk_ca_file"', "sslmode=verify-full", "sslrootcert=$helpdesk_ca_file",
        `host=${endpoint} port=5432 dbname=helpdesk user=helpdesk_app`,
        "default_transaction_read_only=on", "statement_timeout=5000", "lock_timeout=2000",
        "PSQL_HISTORY=/dev/null", "PGCLIENTENCODING=UTF8", "psql -X -W", "ON_ERROR_STOP=on",
        "env -u PGPASSWORD -u PGSERVICE -u PGSERVICEFILE -u PGHOSTADDR",
        'PGPASSFILE="$helpdesk_ca_dir/no-password-file"'
    ]) assert.equal(generated.includes(required), true);
    assert.equal(/\b(?:curl|wget|aws|sudo)\b|rm\s+-rf|sslmode=(?:disable|require|prefer)|password=/.test(generated), false);
    assert.equal(generated.includes('rmdir -- "$helpdesk_ca_dir"'), true);
});

test("explicit approved identifiers can be supplied without adding a Password option", () => {
    const command = buildCloudShellRdsConnectCommand({ endpoint, database: "helpdesk_test", username: "reader_1" });
    assert.equal(command.includes("dbname=helpdesk_test user=reader_1"), true);
    rejectsCode(() => buildCloudShellRdsConnectCommand({ endpoint, password: "synthetic" }), "RDS_CONNECT_OPTIONS_INVALID");
});

test("unsafe targets, other Regions, URIs and identifiers are rejected before generation", () => {
    for (const candidate of [
        "", "localhost", "127.0.0.1", endpoint.replace("ap-northeast-2", "us-east-1"),
        `postgresql://${endpoint}/helpdesk`, `${endpoint}; echo injected`, `${endpoint}\nignored`,
        ` ${endpoint}`, `${endpoint}:5432`, `${endpoint}.untrusted.example`
    ]) rejectsCode(() => buildCloudShellRdsConnectCommand({ endpoint: candidate }), "RDS_CONNECT_OPTIONS_INVALID");
    for (const value of ["", "name with space", "user'", "$(id)", "a".repeat(64)]) {
        rejectsCode(() => buildCloudShellRdsConnectCommand({ endpoint, username: value }), "RDS_CONNECT_OPTIONS_INVALID");
        rejectsCode(() => buildCloudShellRdsConnectCommand({ endpoint, database: value }), "RDS_CONNECT_OPTIONS_INVALID");
    }
});

test("argument parsing refuses missing, duplicate and unrecognized options", () => {
    assert.deepEqual(parseConnectArguments(["--endpoint", endpoint, "--username", "helpdesk_app"]), {
        endpoint, username: "helpdesk_app"
    });
    for (const args of [
        ["--endpoint"], ["--password", "synthetic"], ["--endpoint", endpoint, "--endpoint", endpoint],
        ["--endpoint", endpoint, "extra"]
    ]) rejectsCode(() => parseConnectArguments(args), "RDS_CONNECT_OPTIONS_INVALID");
    rejectsCode(() => buildCloudShellRdsConnectCommand(parseConnectArguments([])), "RDS_CONNECT_OPTIONS_INVALID");
});

test("a local CA checksum mismatch is not silently repaired or downloaded", () => {
    rejectsCode(() => buildCloudShellRdsConnectCommand({ endpoint }, {
        ...assets, bundle: Buffer.concat([assets.bundle, Buffer.from("\n")])
    }), "RDS_CA_HASH_MISMATCH");
});

test("invalid manifests and noncertificate material are rejected even with a matching hash", () => {
    for (const changes of [{ region: "us-east-1" }, { certificateCount: 1 }, { kind: "OTHER" }, { source: "https://example.com/ca" }]) {
        rejectsCode(() => buildCloudShellRdsConnectCommand({ endpoint }, {
            ...assets, manifest: { ...assets.manifest, ...changes }
        }), "RDS_CA_ASSETS_INVALID");
    }
    const bundle = Buffer.concat([assets.bundle, Buffer.from("NOT_A_PUBLIC_CERTIFICATE")]);
    rejectsCode(() => buildCloudShellRdsConnectCommand({ endpoint }, {
        bundle, manifest: { ...assets.manifest, sha256: createHash("sha256").update(bundle).digest("hex") }
    }), "RDS_CA_ASSETS_INVALID");
});

test("CLI returns only the command and never copies environment credentials into it", () => {
    const marker = "SYNTHETIC_VALUE_MUST_NOT_APPEAR";
    const result = cli(["--endpoint", endpoint], { OPENAI_API_KEY: marker, HELPDESK_OPENAI_API_KEY: marker, PGPASSWORD: marker });
    assert.equal(result.status, 0);
    assert.equal(result.stdout === generated && result.stderr === "", true);
    assert.equal(result.stdout.includes(marker), false);
});

test("CLI rejects input without echoing its value or a stack", () => {
    const result = cli(["--endpoint", "SYNTHETIC_REJECTED_VALUE"]);
    assert.equal(result.status, 1);
    assert.equal(result.stdout, "");
    assert.equal(result.stderr, "RDS_CONNECT_OPTIONS_INVALID\n");
});

test("PowerShell wrapper generates the same command without invoking the clipboard", { skip: !pwsh }, () => {
    const result = spawnSync(pwsh, ["-NoProfile", "-File", wrapper, "-Endpoint", endpoint], {
        cwd: root, env: environment({ NODE_OPTIONS: "--invalid-fixture-option" }),
        encoding: "utf8", timeout: 30000, windowsHide: true
    });
    assert.equal(result.status, 0);
    assert.equal(result.stdout.replaceAll("\r\n", "\n"), generated);
    assert.equal(result.stderr, "");
});

test("clipboard mode passes the full command to a Test Double, not the real clipboard", { skip: !pwsh }, () => {
    const quotedWrapper = wrapper.replaceAll("'", "''");
    const command = [
        "function global:Set-Clipboard { param([string]$Value) $global:helpdeskTestCopied = $Value }",
        `& '${quotedWrapper}' -Endpoint '${endpoint}' -CopyToClipboard`,
        "if ($global:helpdeskTestCopied.StartsWith(\"(`n\") -and $global:helpdeskTestCopied.Contains('sslmode=verify-full')) { 'CLIPBOARD_TEST_DOUBLE_OK' } else { exit 1 }"
    ].join("; ");
    const result = spawnSync(pwsh, ["-NoProfile", "-Command", command], {
        env: environment(), encoding: "utf8", timeout: 30000, windowsHide: true
    });
    assert.equal(result.status, 0);
    assert.equal(result.stdout.includes("CLOUDSHELL_CONNECT_COMMAND_COPIED")
        && result.stdout.includes("CLIPBOARD_TEST_DOUBLE_OK"), true);
    assert.equal(result.stderr, "");
});

test("PowerShell rejects an invalid target without exposing it", { skip: !pwsh }, () => {
    const result = spawnSync(pwsh, ["-NoProfile", "-File", wrapper, "-Endpoint", "SYNTHETIC_REJECTED_VALUE"], {
        env: environment(), encoding: "utf8", timeout: 30000, windowsHide: true
    });
    assert.equal(result.status, 1);
    assert.equal(result.stdout, "");
    assert.equal(result.stderr.startsWith("CLOUDSHELL_CONNECT_COMMAND_FAILED"), true);
    assert.equal(result.stderr.includes("SYNTHETIC_REJECTED_VALUE"), false);
});

test("generated Bash syntax is valid", { skip: !bash }, () => {
    const result = spawnSync(bash, ["--noprofile", "--norc", "-n"], {
        input: generated, env: environment(), encoding: "utf8", timeout: 10000, windowsHide: true
    });
    assert.equal(result.status, 0);
    assert.equal(result.stderr, "");
});

function executeWithFakePsql(command, { prelude = "", psqlExit = "0" } = {}) {
    const fixture = mkdtempSync(join(tmpdir(), "helpdesk-cloudshell-connect-test-"));
    const bin = join(fixture, "bin");
    const trace = join(fixture, "trace");
    try {
        mkdirSync(bin);
        writeFileSync(join(bin, "psql"), [
            "#!/usr/bin/env bash", "set -euo pipefail",
            'if [ "${PGPASSWORD+x}" = x ] || [ "${PGSERVICE+x}" = x ] || [ "${PGSERVICEFILE+x}" = x ] || [ "${PGHOSTADDR+x}" = x ]; then exit 91; fi',
            '[ "$PGCLIENTENCODING" = UTF8 ] && [ "$PSQL_HISTORY" = /dev/null ]',
            "[ \"$PGOPTIONS\" = '-c default_transaction_read_only=on -c statement_timeout=5000 -c lock_timeout=2000' ]",
            '[ ! -e "$PGPASSFILE" ]',
            '[ "$1" = -X ] && [ "$2" = -W ]',
            'helpdesk_test_connection="${!#}"',
            `case "$helpdesk_test_connection" in 'host=${endpoint} port=5432 dbname=helpdesk user=helpdesk_app sslmode=verify-full sslrootcert='*' connect_timeout=10') ;; *) exit 92 ;; esac`,
            'helpdesk_test_ca="${helpdesk_test_connection#*sslrootcert=}"',
            'helpdesk_test_ca="${helpdesk_test_ca%% *}"',
            `printf '%s  %s\\n' '${assets.manifest.sha256}' "$helpdesk_test_ca" | sha256sum --check --status`,
            'case "$OSTYPE" in linux*) [ "$(stat -c %a "$helpdesk_test_ca")" = 400 ] ;; esac',
            'printf \'%s\\n\' "$helpdesk_test_ca" >> "$HELPDESK_TEST_TRACE_FILE"',
            "printf '%s\\n' 'FAKE_PSQL_OPTIONS_AND_CA_OK'",
            'exit "$HELPDESK_TEST_PSQL_EXIT_CODE"', ""
        ].join("\n"), { mode: 0o700 });
        chmodSync(join(bin, "psql"), 0o700);
        const input = [
            'export PATH="$1:$PATH"', prelude, command,
            "helpdesk_test_status=$?",
            'if [ -f "$HELPDESK_TEST_TRACE_FILE" ]; then',
            '  while IFS= read -r helpdesk_recorded_ca; do',
            '    if [ -e "$helpdesk_recorded_ca" ] || [ -d "${helpdesk_recorded_ca%/*}" ]; then exit 93; fi',
            '  done < "$HELPDESK_TEST_TRACE_FILE"',
            "  printf '%s\\n' 'CA_CLEANED_AFTER_PSQL'", "fi",
            'exit "$helpdesk_test_status"', ""
        ].join("\n");
        const result = spawnSync(bash, ["--noprofile", "--norc", "-s", "--", bashPath(bin)], {
            input, encoding: "utf8", timeout: 20000, windowsHide: true,
            env: environment({
                PGPASSWORD: "SYNTHETIC_UNUSED", PGSERVICE: "SYNTHETIC_UNUSED",
                PGSERVICEFILE: "SYNTHETIC_UNUSED", PGHOSTADDR: "SYNTHETIC_UNUSED",
                HELPDESK_TEST_TRACE_FILE: bashPath(trace), HELPDESK_TEST_PSQL_EXIT_CODE: psqlExit
            })
        });
        return { ...result, calls: existsSync(trace) ? readFileSync(trace, "utf8").trim().split("\n") : [] };
    } finally {
        // Delete only this Test's newly allocated fixture, never a supplied/user path.
        assert.equal(resolve(fixture).startsWith(resolve(tmpdir()) + "/")
            || resolve(fixture).startsWith(resolve(tmpdir()) + "\\"), true);
        assert.equal(fixture.includes("helpdesk-cloudshell-connect-test-"), true);
        rmSync(fixture, { recursive: true, force: true });
    }
}

test("two fresh shell runs independently restore CA, clear saved credentials and clean their files", { skip: !bash }, () => {
    const paths = [];
    for (let index = 0; index < 2; index += 1) {
        const result = executeWithFakePsql(generated);
        assert.equal(result.status, 0);
        assert.equal(result.stderr, "");
        assert.equal(result.stdout.includes("CLOUDSHELL_RDS_CA_VERIFIED")
            && result.stdout.includes("FAKE_PSQL_OPTIONS_AND_CA_OK")
            && result.stdout.includes("CA_CLEANED_AFTER_PSQL"), true);
        assert.equal(result.calls.length, 1);
        paths.push(result.calls[0]);
    }
    assert.equal(paths[0] === paths[1], false);
});

test("runtime CA checksum failure stops before psql", { skip: !bash }, () => {
    const result = executeWithFakePsql(generated.replace(assets.manifest.sha256, "0".repeat(64)));
    assert.notEqual(result.status, 0);
    assert.equal(result.stderr.includes("RDS_CA_HASH_MISMATCH"), true);
    assert.equal(result.calls.length, 0);
});

test("psql failure is not retried and its temporary CA is still cleaned", { skip: !bash }, () => {
    const result = executeWithFakePsql(generated, { psqlExit: "17" });
    assert.equal(result.status, 17);
    assert.equal(result.calls.length, 1);
    assert.equal(result.stdout.includes("CA_CLEANED_AFTER_PSQL"), true);
});

test("a missing psql tool stops without installing anything or attempting a connection", { skip: !bash }, () => {
    const result = executeWithFakePsql(generated, {
        prelude: 'command() { if [ "$1" = -v ] && [ "$2" = psql ]; then return 1; else builtin command "$@"; fi; }'
    });
    assert.equal(result.status, 1);
    assert.equal(result.stderr.includes("REQUIRED_TOOL_MISSING: psql"), true);
    assert.equal(result.calls.length, 0);
});

test("guide explains ephemeral storage, manual Password and the Test Double evidence boundary", () => {
    const guide = read("deploy/iam/db-bootstrap.md");
    for (const phrase of [
        "New-CloudShellRdsConnectCommand.ps1", "-CopyToClipboard", "홈 디렉터리까지 삭제",
        "Test Double", "실제 RDS 접속 근거가 아니다", "App DB 암호", "기본 실행 설정"
    ]) assert.equal(guide.includes(phrase), true);
});
