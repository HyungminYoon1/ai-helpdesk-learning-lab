import { Buffer } from "node:buffer";
import { createHash, X509Certificate } from "node:crypto";
import { readFileSync } from "node:fs";
import { pathToFileURL, URL } from "node:url";
import { gzipSync } from "node:zlib";

const CA_SOURCE = "https://truststore.pki.rds.amazonaws.com/ap-northeast-2/ap-northeast-2-bundle.pem";
const CA_DELIMITER = "HELPDESK_PUBLIC_RDS_CA_GZIP_BASE64";
const IDENTIFIER = /^[a-z_][a-z0-9_]{0,62}$/;
const ENDPOINT = /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.[a-z0-9-]+\.ap-northeast-2\.rds\.amazonaws\.com$/;
const ERROR_CODES = new Set([
    "RDS_CONNECT_OPTIONS_INVALID",
    "RDS_CA_ASSETS_INVALID",
    "RDS_CA_HASH_MISMATCH"
]);

function reviewedCaAssets() {
    try {
        return {
            bundle: readFileSync(new URL("../docker/rds/ap-northeast-2-bundle.pem", import.meta.url)),
            manifest: JSON.parse(readFileSync(
                new URL("../docker/rds/bundle-manifest.json", import.meta.url), "utf8"))
        };
    } catch {
        throw new Error("RDS_CA_ASSETS_INVALID");
    }
}

function validateCa({ bundle, manifest }) {
    if (!Buffer.isBuffer(bundle) || bundle.length > 65536 || !manifest
        || manifest.region !== "ap-northeast-2"
        || manifest.kind !== "PUBLIC_RDS_ROOT_CA_BUNDLE"
        || manifest.source !== CA_SOURCE
        || manifest.certificateCount !== 3
        || !/^[a-f0-9]{64}$/.test(manifest.sha256 ?? "")) {
        throw new Error("RDS_CA_ASSETS_INVALID");
    }
    if (createHash("sha256").update(bundle).digest("hex") !== manifest.sha256) {
        throw new Error("RDS_CA_HASH_MISMATCH");
    }

    const text = bundle.toString("utf8");
    const expression = /-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----/g;
    const blocks = text.match(expression) ?? [];
    if (blocks.length !== manifest.certificateCount || text.replace(expression, "").trim() !== "") {
        throw new Error("RDS_CA_ASSETS_INVALID");
    }
    for (const block of blocks) {
        try {
            const certificate = new X509Certificate(block);
            if (!certificate.ca || certificate.subject !== certificate.issuer
                || !certificate.verify(certificate.publicKey)
                || Date.parse(certificate.validFrom) > Date.now()
                || Date.parse(certificate.validTo) <= Date.now()) {
                throw new Error("RDS_CA_ASSETS_INVALID");
            }
        } catch {
            throw new Error("RDS_CA_ASSETS_INVALID");
        }
    }
}

// Local command generation only: no network, environment credentials or Database access.
export function buildCloudShellRdsConnectCommand(options, assets = reviewedCaAssets()) {
    if (!options || typeof options !== "object" || Array.isArray(options)
        || Object.keys(options).some(key => !["endpoint", "database", "username"].includes(key))) {
        throw new Error("RDS_CONNECT_OPTIONS_INVALID");
    }
    const { endpoint, database = "helpdesk", username = "helpdesk_app" } = options;
    if (typeof endpoint !== "string" || endpoint.length > 253 || !ENDPOINT.test(endpoint)
        || typeof database !== "string" || !IDENTIFIER.test(database)
        || typeof username !== "string" || !IDENTIFIER.test(username)) {
        throw new Error("RDS_CONNECT_OPTIONS_INVALID");
    }
    validateCa(assets);
    const payload = gzipSync(assets.bundle, { level: 9 }).toString("base64")
        .match(/.{1,76}/g).join("\n");

    // A subshell keeps options, umask and variables out of the user's interactive shell.
    // Only the fresh directory's one public CA file is removed; no recursive deletion.
    return [
        "(",
        "  set +x",
        "  set -euo pipefail",
        "  umask 077",
        "  for helpdesk_tool in base64 gzip sha256sum mktemp chmod rm rmdir env psql; do",
        '    if ! command -v "$helpdesk_tool" >/dev/null 2>&1; then',
        '      printf \'REQUIRED_TOOL_MISSING: %s\\n\' "$helpdesk_tool" >&2',
        "      exit 1",
        "    fi",
        "  done",
        '  helpdesk_psql_version="$(psql --version)"',
        '  case "$helpdesk_psql_version" in',
        "    'psql (PostgreSQL) 17.'[0-9]*) ;;",
        "    *) printf '%s\\n' 'PSQL17_REQUIRED' >&2; exit 1 ;;",
        "  esac",
        '  helpdesk_ca_dir="$(mktemp -d /tmp/helpdesk-rds-XXXXXXXXXX)"',
        '  helpdesk_ca_file="$helpdesk_ca_dir/rds-ca-bundle.pem"',
        '  trap \'rm -f -- "$helpdesk_ca_file"; rmdir -- "$helpdesk_ca_dir"\' EXIT',
        `  base64 --decode <<'${CA_DELIMITER}' | gzip --decompress --stdout > "$helpdesk_ca_file"`,
        payload,
        CA_DELIMITER,
        `  if ! printf '%s  %s\\n' '${assets.manifest.sha256}' "$helpdesk_ca_file" | sha256sum --check --status; then`,
        "    printf '%s\\n' 'RDS_CA_HASH_MISMATCH' >&2",
        "    exit 1",
        "  fi",
        '  chmod 0400 "$helpdesk_ca_file"',
        "  printf '%s\\n' 'CLOUDSHELL_RDS_CA_VERIFIED'",
        "  env -u PGPASSWORD -u PGSERVICE -u PGSERVICEFILE -u PGHOSTADDR \\",
        '    PGPASSFILE="$helpdesk_ca_dir/no-password-file" \\',
        "    PGCLIENTENCODING=UTF8 PSQL_HISTORY=/dev/null \\",
        "    PGOPTIONS='-c default_transaction_read_only=on -c statement_timeout=5000 -c lock_timeout=2000' \\",
        "    psql -X -W --set=ON_ERROR_STOP=on --pset=pager=off \\",
        `    "host=${endpoint} port=5432 dbname=${database} user=${username} sslmode=verify-full sslrootcert=$helpdesk_ca_file connect_timeout=10"`,
        ")",
        ""
    ].join("\n");
}

export function parseConnectArguments(args) {
    const options = {};
    const names = new Map([
        ["--endpoint", "endpoint"], ["--database", "database"], ["--username", "username"]
    ]);
    for (let index = 0; index < args.length; index += 2) {
        const name = names.get(args[index]);
        if (!name || Object.hasOwn(options, name) || typeof args[index + 1] !== "string") {
            throw new Error("RDS_CONNECT_OPTIONS_INVALID");
        }
        options[name] = args[index + 1];
    }
    return options;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
    try {
        if (process.argv.length === 3 && process.argv[2] === "--help") {
            process.stdout.write("node scripts/cloudshell-rds-connect.mjs --endpoint <RDS_ENDPOINT> [--database helpdesk] [--username helpdesk_app]\n");
        } else {
            process.stdout.write(buildCloudShellRdsConnectCommand(parseConnectArguments(process.argv.slice(2))));
        }
    } catch (error) {
        // Never echo rejected arguments, environment values, raw errors or stacks.
        process.stderr.write(`${ERROR_CODES.has(error?.message) ? error.message : "RDS_CONNECT_COMMAND_FAILED"}\n`);
        process.exitCode = 1;
    }
}
