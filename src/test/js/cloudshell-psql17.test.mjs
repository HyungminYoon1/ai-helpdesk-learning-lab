import assert from "node:assert/strict";
import { Buffer } from "node:buffer";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { dirname, delimiter } from "node:path";
import test from "node:test";
import { fileURLToPath, URL } from "node:url";
import { gzipSync } from "node:zlib";
import { buildCloudShellPsql17Command, splitCloudShellPsql17Command } from "../../../scripts/cloudshell-psql17.mjs";

const root = new URL("../../../", import.meta.url);
const manifest = JSON.parse(readFileSync(new URL("deploy/cloudshell/psql17-manifest.json", root), "utf8"));
const files = ["bin/psql", "bin/psql.real", "lib/libpq.so.private17-5", "COPYRIGHT"];

function assets({ names = files, type = 48, link = "" } = {}) {
    const entries = names.map(name => {
        const header = Buffer.alloc(512);
        header.write(name, 0);
        header.write("00000000001\0", 124);
        header[156] = type;
        header.write(link, 157);
        header.fill(32, 148, 156);
        const checksum = header.reduce((sum, value) => sum + value, 0).toString(8).padStart(6, "0");
        header.write(`${checksum}\0 `, 148);
        return Buffer.concat([header, Buffer.alloc(512, 0)]);
    });
    const archive = gzipSync(Buffer.concat([...entries, Buffer.alloc(1024)]));
    return { archive, manifest: { ...manifest, archiveBytes: archive.length,
        sha256: createHash("sha256").update(archive).digest("hex") } };
}

test("offline command validates content then extracts only four reviewed regular files", () => {
    const command = buildCloudShellPsql17Command(assets());
    for (const required of ["sha256sum --check --status", "--no-same-owner --no-same-permissions",
        "mktemp -d /tmp/helpdesk-psql17-XXXXXXXXXX", "psql (PostgreSQL) 17.11",
        'export PATH="$helpdesk_psql17_dir/bin:$PATH"', "PSQL17_PREPARATION_FAILED"]) {
        assert.equal(command.includes(required), true);
    }
    assert.equal(/\b(?:sudo|dnf|yum|curl|wget|aws|psql_server|eval)\b|password=|PGPASSWORD|rm\s+-rf/.test(command), false);
});

test("small paste parts preserve every byte, line boundary and order", () => {
    const command = buildCloudShellPsql17Command(assets()) + "# synthetic public padding\n".repeat(2500);
    const parts = splitCloudShellPsql17Command(command);
    assert.equal(parts.length > 3, true);
    assert.equal(parts.every(part => part.length > 0 && part.length <= 20000 && part.endsWith("\n")), true);
    assert.equal(parts.join(""), command);
    assert.equal(splitCloudShellPsql17Command("final line").join(""), "final line");
    assert.throws(() => splitCloudShellPsql17Command("x".repeat(20001)), /PSQL17_ASSETS_INVALID/);
    assert.throws(() => splitCloudShellPsql17Command(""), /PSQL17_ASSETS_INVALID/);
});

test("checksum mismatch, wrong platform or version and truncated payload are rejected", () => {
    for (const change of [{ sha256: "0".repeat(64) }, { version: "16.15" }, { platform: "linux/arm64" }]) {
        const input = assets();
        input.manifest = { ...input.manifest, ...change };
        assert.throws(() => buildCloudShellPsql17Command(input), /PSQL17_ASSETS_INVALID/);
    }
    assert.throws(() => buildCloudShellPsql17Command({ ...assets(), archive: Buffer.from("bad") }), /PSQL17_ASSETS_INVALID/);
});

test("missing or extra files, traversal and symbolic/hard links cannot enter the payload", () => {
    for (const input of [assets({ names: files.slice(1) }), assets({ names: [...files, "server/postgres"] }),
        assets({ names: ["../psql", ...files.slice(1)] }), assets({ type: 50, link: "/usr/bin/psql" }),
        assets({ type: 49, link: "bin/psql" })]) {
        assert.throws(() => buildCloudShellPsql17Command(input), /PSQL17_ASSETS_INVALID/);
    }
});

const bash = process.platform === "win32" ? "C:/Program Files/Git/bin/bash.exe" : "bash";
const safeEnvironment = { PATH: [dirname(process.execPath), process.env.PATH ?? process.env.Path ?? ""].join(delimiter) };
for (const name of ["SystemRoot", "TEMP", "TMP", "PATHEXT"]) {
    if (process.env[name] !== undefined) safeEnvironment[name] = process.env[name];
}
const bashAvailable = spawnSync(bash, ["--version"], { env: safeEnvironment, windowsHide: true }).status === 0;

test("generated offline preparation has valid Bash syntax", { skip: !bashAvailable }, () => {
    const result = spawnSync(bash, ["--noprofile", "--norc", "-n"], {
        input: buildCloudShellPsql17Command(assets()), env: safeEnvironment, encoding: "utf8", windowsHide: true
    });
    assert.equal(result.status, 0);
    assert.equal(result.stderr, "");
});

test("CLI refuses arguments without reading assets or echoing input", () => {
    for (const args of [["SYNTHETIC_REJECTED_ARGUMENT"], ["--part", "0"], ["--part", "-1"],
        ["--part", "1;echo"], ["--part-count", "extra"]]) {
        const result = spawnSync(process.execPath, [fileURLToPath(new URL("scripts/cloudshell-psql17.mjs", root)),
            ...args], { env: safeEnvironment, encoding: "utf8", windowsHide: true });
        assert.equal(result.status, 1);
        assert.equal(result.stdout, "");
        assert.equal(result.stderr, "PSQL17_ASSETS_INVALID\n");
    }
});

test("local builder pins the official package and exports no DB server", () => {
    const dockerfile = readFileSync(new URL("deploy/cloudshell/Dockerfile", root), "utf8");
    const wrapper = readFileSync(new URL("deploy/cloudshell/psql17-wrapper.sh", root), "utf8");
    assert.equal(dockerfile.includes(manifest.baseImage), true);
    assert.equal(dockerfile.includes("--releasever=2023.12.20260930"), true);
    assert.equal(dockerfile.includes("postgresql17-17.11-1.amzn2023.0.1"), true);
    assert.equal(dockerfile.includes("postgresql17-server"), false);
    assert.equal(wrapper.includes('-u LD_PRELOAD -u LD_AUDIT'), true);
    assert.equal(wrapper.includes('LD_LIBRARY_PATH="$helpdesk_psql_root/lib"'), true);
});
