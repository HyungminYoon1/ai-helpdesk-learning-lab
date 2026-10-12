import assert from "node:assert/strict";
import { createHash, X509Certificate } from "node:crypto";
import { readFileSync } from "node:fs";
import test from "node:test";
import { URL } from "node:url";

const read = (path) => readFileSync(new URL(`../../../${path}`, import.meta.url), "utf8");
const bundle = read("docker/rds/ap-northeast-2-bundle.pem");
const dockerfile = read("Dockerfile");
const ignore = read(".dockerignore");
let manifest;
try {
  manifest = JSON.parse(read("docker/rds/bundle-manifest.json"));
} catch {
  throw new Error("RDS_CA_MANIFEST_INVALID");
}
const publicBlocks = bundle.match(/-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----/g) ?? [];

test("RDS CA manifest pins the public Seoul bundle bytes and image path", () => {
  assert.equal(manifest.region === "ap-northeast-2"
    && manifest.kind === "PUBLIC_RDS_ROOT_CA_BUNDLE", true);
  assert.equal(manifest.source === "https://truststore.pki.rds.amazonaws.com/ap-northeast-2/ap-northeast-2-bundle.pem", true);
  assert.equal(manifest.imagePath === "/app/certs/rds-ca-bundle.pem", true);
  assert.equal(createHash("sha256").update(bundle, "utf8").digest("hex") === manifest.sha256, true);
});

test("RDS trust bundle contains only three public certificate blocks", () => {
  assert.equal(publicBlocks.length === 3 && manifest.certificateCount === 3, true);
  assert.equal(bundle.replace(/-----BEGIN CERTIFICATE-----[\s\S]+?-----END CERTIFICATE-----/g, "").trim() === "", true);
  assert.equal(bundle.includes("PRIVATE KEY"), false);
});

test("RDS bundle certificates are currently valid self-signed Seoul roots", () => {
  const expectedNames = new Set(["ECC384 G1", "RSA2048 G1", "RSA4096 G1"]);
  for (const pem of publicBlocks) {
    let cert;
    try {
      cert = new X509Certificate(pem);
    } catch {
      throw new Error("RDS_CA_CERTIFICATE_INVALID");
    }
    const name = cert.subject.match(/CN=Amazon RDS ap-northeast-2 Root CA ([^\n]+)/)?.[1];
    assert.equal(expectedNames.delete(name), true);
    assert.equal(cert.ca && cert.subject === cert.issuer && cert.verify(cert.publicKey), true);
    assert.equal(Date.parse(cert.validFrom) <= Date.now() && Date.now() < Date.parse(cert.validTo), true);
  }
  assert.equal(expectedNames.size === 0, true);
});

test("runtime image copies the reviewed bundle as a read-only nonroot-readable file", () => {
  const copy = "COPY --chown=0:0 --chmod=0444 docker/rds/ap-northeast-2-bundle.pem ./certs/rds-ca-bundle.pem";
  assert.equal(dockerfile.includes(copy), true);
  assert.equal(dockerfile.indexOf(copy) > dockerfile.indexOf("AS runtime"), true);
  assert.equal(dockerfile.indexOf("RUN chmod 0555 /app/certs") > dockerfile.indexOf(copy)
    && dockerfile.indexOf("RUN chmod 0555 /app/certs") < dockerfile.indexOf("USER 10001:10001"), true);
  assert.equal(dockerfile.includes("USER 10001:10001")
    && dockerfile.includes('ENTRYPOINT ["java", "-jar", "/app/helpdesk.jar"]'), true);
});

test("Docker context keeps broad PEM and key exclusions with one exact public exception", () => {
  const lines = ignore.split("\n").map((line) => line.trim());
  assert.equal(lines.includes("**/*.pem") && lines.includes("**/*.key")
    && lines.includes("**/.env") && lines.includes("**/.env.*"), true);
  const exceptions = lines.filter((line) => line.startsWith("!") && line.endsWith(".pem"));
  assert.equal(exceptions.length === 1
    && exceptions[0] === "!docker/rds/ap-northeast-2-bundle.pem", true);
  assert.equal(ignore.trimEnd().endsWith(exceptions[0]), true);
});

test("RDS CA assembly does not fetch certificates on startup or change the global truststore", () => {
  assert.equal(/\b(?:curl|wget|keytool)\b|javax\.net\.ssl\.trustStore/.test(dockerfile), false);
  assert.equal(dockerfile.includes("COPY docker/rds/") || dockerfile.includes("COPY . "), false);
});
