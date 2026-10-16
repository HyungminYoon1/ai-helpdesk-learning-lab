import { Buffer } from "node:buffer";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { pathToFileURL, URL } from "node:url";
import { gunzipSync } from "node:zlib";

const FILES = ["bin/psql", "bin/psql.real", "lib/libpq.so.private17-5", "COPYRIGHT"];
const INVALID = "PSQL17_ASSETS_INVALID";

function reviewedAssets() {
    return {
        archive: readFileSync(new URL("../local/tools/psql17/bundle/psql17.tar.gz", import.meta.url)),
        manifest: JSON.parse(readFileSync(new URL("../deploy/cloudshell/psql17-manifest.json", import.meta.url), "utf8"))
    };
}

function validateAssets({ archive, manifest }) {
    if (!Buffer.isBuffer(archive) || archive.length > 2 * 1024 * 1024
        || manifest?.kind !== "OFFICIAL_AL2023_PSQL_CLIENT_ONLY"
        || manifest.version !== "17.11" || manifest.platform !== "linux/amd64"
        || manifest.archiveBytes !== archive.length || !/^[a-f0-9]{64}$/.test(manifest.sha256 ?? "")
        || createHash("sha256").update(archive).digest("hex") !== manifest.sha256) throw Error(INVALID);

    // Only four regular files may be extracted. No paths, links or extra tools supplied by input.
    const tar = gunzipSync(archive, { maxOutputLength: 4 * 1024 * 1024 });
    const seen = new Set();
    for (let offset = 0; offset + 512 <= tar.length;) {
        const header = tar.subarray(offset, offset + 512);
        if (header.every(value => value === 0)) {
            if (tar.subarray(offset).some(value => value !== 0)) throw Error(INVALID);
            break;
        }
        const name = header.subarray(0, 100).toString("utf8").split("\0")[0];
        const sizeText = header.subarray(124, 136).toString("ascii").replaceAll("\0", "").trim();
        const size = /^[0-7]+$/.test(sizeText) ? Number.parseInt(sizeText, 8) : -1;
        if (!FILES.includes(name) || seen.has(name) || ![0, 48].includes(header[156])
            || header.subarray(157, 257).some(value => value !== 0)
            || header.subarray(345, 500).some(value => value !== 0)
            || size < 1 || offset + 512 + size > tar.length) throw Error(INVALID);
        seen.add(name);
        offset += 512 + Math.ceil(size / 512) * 512;
    }
    if (seen.size !== FILES.length) throw Error(INVALID);
}

// Generates a public offline payload only. Does not read credentials or access any network/DB.
export function buildCloudShellPsql17Command(assets = reviewedAssets()) {
    validateAssets(assets);
    const payload = assets.archive.toString("base64").match(/.{1,76}/g).join("\n");
    return [
        "helpdesk_activate_psql17() {",
        "  local helpdesk_psql17_dir",
        '  helpdesk_psql17_dir="$(mktemp -d /tmp/helpdesk-psql17-XXXXXXXXXX)" || return 1',
        '  if bash --noprofile --norc -euo pipefail -s -- "$helpdesk_psql17_dir" <<\'HELPDESK_PSQL17_SETUP\'',
        "set +x", "umask 077", 'helpdesk_psql17_dir="$1"',
        '[ "$(uname -m)" = x86_64 ]',
        "for helpdesk_tool in base64 sha256sum tar chmod env; do command -v \"$helpdesk_tool\" >/dev/null; done",
        'cd -- "$helpdesk_psql17_dir"',
        "base64 --decode > client.tar.gz <<'HELPDESK_PSQL17_ARCHIVE'", payload,
        "HELPDESK_PSQL17_ARCHIVE",
        `printf '%s  %s\\n' '${assets.manifest.sha256}' client.tar.gz | sha256sum --check --status`,
        "tar --no-same-owner --no-same-permissions -xzf client.tar.gz -- " + FILES.join(" "),
        "chmod 0500 bin/psql bin/psql.real lib/libpq.so.private17-5",
        "chmod 0400 COPYRIGHT client.tar.gz",
        '[ "$(bin/psql --version)" = "psql (PostgreSQL) 17.11" ]',
        "HELPDESK_PSQL17_SETUP",
        "  then",
        '    export PATH="$helpdesk_psql17_dir/bin:$PATH"', "    hash -r",
        "    psql --version",
        "  else", "    printf '%s\\n' 'PSQL17_PREPARATION_FAILED' >&2", "    return 1", "  fi", "}",
        "if helpdesk_activate_psql17; then", "  unset -f helpdesk_activate_psql17",
        "else", "  unset -f helpdesk_activate_psql17", "  false", "fi", ""
    ].join("\n");
}

// CloudShell can silently truncate a large paste. Keep every part below 20,000 characters.
// Parts retain their newlines and must be pasted, in order, into the same shell.
export function splitCloudShellPsql17Command(command) {
    if (typeof command !== "string" || command.length === 0) throw Error(INVALID);
    const parts = [];
    let part = "";
    for (const line of command.match(/[^\n]*\n|[^\n]+$/g) ?? []) {
        if (line.length > 20000) throw Error(INVALID);
        if (part.length + line.length > 20000) {
            parts.push(part);
            part = "";
        }
        part += line;
    }
    if (part) parts.push(part);
    return parts;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    try {
        if (process.argv.length === 3 && process.argv[2] === "--help") {
            process.stdout.write("Build deploy/cloudshell locally. Use --part-count, then --part N; paste each part in order into the same existing CloudShell VPC shell. No Password belongs in this command. With no options, stdout contains the complete command for programmatic use only.\n");
        } else {
            const args = process.argv.slice(2);
            const countOnly = args.length === 1 && args[0] === "--part-count";
            const partOnly = args.length === 2 && args[0] === "--part" && /^[1-9][0-9]{0,3}$/.test(args[1]);
            if (args.length !== 0 && !countOnly && !partOnly) throw Error(INVALID);
            const command = buildCloudShellPsql17Command();
            if (args.length === 0) {
                process.stdout.write(command);
            } else {
                const parts = splitCloudShellPsql17Command(command);
                if (countOnly) process.stdout.write(`${parts.length}\n`);
                else {
                    const part = parts[Number(args[1]) - 1];
                    if (part === undefined) throw Error(INVALID);
                    process.stdout.write(part);
                }
            }
        }
    } catch {
        process.stderr.write(`${INVALID}\n`);
        process.exitCode = 1;
    }
}
