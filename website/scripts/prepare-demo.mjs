import { createHash } from "node:crypto";
import { copyFile, mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { basename, dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const manifest = JSON.parse(
  await readFile(resolve(root, "demo-runtime.json"), "utf8"),
);
const output = resolve(root, "lib/kmpwright-demo");
const verifyOnly = process.argv.includes("--verify-local");
const hash = (bytes) => createHash("sha256").update(bytes).digest("hex");

await mkdir(output, { recursive: true });
for (const asset of manifest.assets) {
  if (
    basename(asset.file) !== asset.file ||
    !/^[a-f0-9]{64}$/.test(asset.sha256)
  ) {
    throw new Error("Invalid demo runtime manifest");
  }
  if (verifyOnly && asset.file.endsWith(".LICENSE.txt")) continue;
  const destination = resolve(output, asset.file);
  const existing = await readFile(destination).catch(() => null);
  if (existing && hash(existing) === asset.sha256) continue;
  if (verifyOnly) {
    throw new Error(
      `Demo runtime is stale: ${asset.file}. Publish an updated runtime and manifest.`,
    );
  }
  const response = await fetch(new URL(asset.file, manifest.baseUrl), {
    signal: AbortSignal.timeout(60_000),
  });
  if (!response.ok)
    throw new Error(`Cannot download ${asset.file}: HTTP ${response.status}`);
  const bytes = Buffer.from(await response.arrayBuffer());
  if (hash(bytes) !== asset.sha256)
    throw new Error(`Checksum mismatch: ${asset.file}`);
  await writeFile(`${destination}.tmp`, bytes);
  await rename(`${destination}.tmp`, destination);
}

if (!verifyOnly) {
  const licenses = resolve(root, "public/licenses");
  await mkdir(licenses, { recursive: true });
  await copyFile(
    resolve(output, "kmpwright-demo.LICENSE.txt"),
    resolve(licenses, "kmpwright-demo.txt"),
  );
}
console.log(`Demo runtime ${manifest.release}: checksums verified`);
