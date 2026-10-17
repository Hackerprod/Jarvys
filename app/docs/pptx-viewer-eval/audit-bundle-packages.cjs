"use strict";

const fs = require("node:fs");
const path = require("node:path");

const moduleRoot = path.resolve(__dirname, "../../app/src/main/assets/modules/presentation");
const bundlePath = path.resolve(process.argv[2] || path.join(moduleRoot, "vendor/pptx-glimpse.bundle.js"));
const buildRoot = path.resolve(process.argv[3] || path.join(
  process.env.LOCALAPPDATA || process.env.TEMP || process.cwd(),
  "Temp",
  "jarvys-pptx-glimpse-browser-build",
));
const bundle = fs.readFileSync(bundlePath, "utf8");
const regions = [...bundle.matchAll(/^\/\/#region (.+)$/gm)].map((match) => match[1]);

function packageNameFromPath(sourcePath) {
  const marker = "node_modules/";
  const index = sourcePath.lastIndexOf(marker);
  if (index < 0) return null;
  const parts = sourcePath.slice(index + marker.length).split("/");
  return parts[0].startsWith("@") ? `${parts[0]}/${parts[1]}` : parts[0];
}

function packageNameFromSpecifier(specifier) {
  if (specifier.startsWith(".") || specifier.startsWith("/") || specifier.startsWith("node:")) return null;
  if (!/^(?:@[\w.-]+\/)?[\w.-]+(?:\/[\w.-]+)*$/.test(specifier)) return null;
  const parts = specifier.split("/");
  return parts[0].startsWith("@") ? `${parts[0]}/${parts[1]}` : parts[0];
}

const regionPackages = [...new Set(regions.map(packageNameFromPath).filter(Boolean))].sort();
const nestedPackages = [...bundle.matchAll(
  /node_modules\/\.pnpm\/([^/]+)\/node_modules\/((?:@[^/]+\/)?[^/\r\n]+)/g,
)];
const nestedPackageIds = [...new Set(nestedPackages.map((match) => match[1]))].sort();
const nestedPackageNames = [...new Set(nestedPackages.map((match) => match[2]))].sort();
const importedPackages = new Set();
const sourceImportEvidence = [];

for (const sourcePath of regions) {
  const packagePath = packageNameFromPath(sourcePath);
  if (!packagePath) continue;
  const relativeSourcePath = sourcePath.slice(sourcePath.lastIndexOf("node_modules/") + "node_modules/".length);
  const absoluteSourcePath = path.join(buildRoot, "node_modules", relativeSourcePath);
  if (!fs.existsSync(absoluteSourcePath)) continue;
  const source = fs.readFileSync(absoluteSourcePath, "utf8");
  for (const match of source.matchAll(/(?:from\s*|import\s*)["']([^"']+)["']/g)) {
    const importedPackage = packageNameFromSpecifier(match[1]);
    if (!importedPackage) continue;
    importedPackages.add(importedPackage);
    sourceImportEvidence.push(`${packagePath} -> ${importedPackage}`);
  }
}

const inlineChecks = [
  ["is-unsafe", bundle.includes("is-unsafe: first argument must be a string")],
  ["xml-naming", bundle.includes("qName: RegExp")],
  ["opentype.js", bundle.includes("new XMLHttpRequest()")],
];
const glimpseManifest = JSON.parse(fs.readFileSync(path.join(buildRoot, "node_modules/pptx-glimpse/package.json"), "utf8"));
const opentypePath = path.join(buildRoot, "node_modules/opentype.js");
const opentypeManifest = JSON.parse(fs.readFileSync(path.join(opentypePath, "package.json"), "utf8"));
const opentypeVersion = opentypeManifest.version;
const opentypeDependencies = Object.entries(opentypeManifest.dependencies || {});
const expectedOpentypeDependencies = new Map([
  ["tiny-inflate", "1.0.3"],
  ["string.prototype.codepointat", "0.2.1"],
]);
if (opentypeDependencies.length !== expectedOpentypeDependencies.size) {
  throw new Error(`Expected ${expectedOpentypeDependencies.size} runtime dependencies for opentype.js; found ${opentypeDependencies.length}.`);
}
const transitiveCodeChecks = new Map([
  ["tiny-inflate", bundle.includes("new Uint16Array(288)") && bundle.includes("Wa(n, r)")],
  ["string.prototype.codepointat", bundle.includes("String.prototype.codePointAt || (function()")],
]);
const includedOpentypeDependencies = [];
for (const [name, range] of opentypeDependencies) {
  const dependencyPath = path.join(buildRoot, "node_modules", name);
  const manifest = JSON.parse(fs.readFileSync(path.join(dependencyPath, "package.json"), "utf8"));
  const licenseFile = fs.readdirSync(dependencyPath).find((entry) => /^LICENSE(?:-[^.]*)?(?:\.[^.]*)?$/i.test(entry));
  const codePresent = transitiveCodeChecks.get(name);
  if (Object.keys(manifest.dependencies || {}).length !== 0) {
    throw new Error(`Unexpected additional runtime dependencies of ${name}@${manifest.version}: ${Object.keys(manifest.dependencies).join(", ")}.`);
  }
  if (!licenseFile) throw new Error(`No license file found for ${name}@${manifest.version}.`);
  if (manifest.version !== expectedOpentypeDependencies.get(name)) {
    throw new Error(`Unexpected resolved version for ${name}: ${manifest.version}.`);
  }
  if (!codePresent) throw new Error(`No bundled implementation signature found for ${name}@${manifest.version}.`);
  includedOpentypeDependencies.push(name);
  const licenseText = fs.readFileSync(path.join(dependencyPath, licenseFile), "utf8").replaceAll("\r\n", "\n").trim();
  const notices = fs.readFileSync(path.join(moduleRoot, "vendor/THIRD-PARTY-NOTICES.md"), "utf8").replaceAll("\r\n", "\n");
  if (!notices.includes(licenseText)) throw new Error(`The full ${licenseFile} text is missing from THIRD-PARTY-NOTICES.md for ${name}.`);
  console.log(`opentype.js runtime dependency: ${name}@${manifest.version} (${range}); license=${manifest.license}; file=${licenseFile}; bundled=${codePresent}; notice=complete`);
  if (manifest.license !== "MIT") throw new Error(`Unexpected license for ${name}@${manifest.version}: ${manifest.license}.`);
}
const packageInventory = [...new Set([
  ...regionPackages,
  ...nestedPackageNames,
  ...importedPackages,
  ...(opentypeVersion && inlineChecks[2][1] ? ["opentype.js", ...includedOpentypeDependencies] : []),
])].sort();

console.log(`Bundle: ${bundlePath}`);
console.log(`Source regions: ${regions.length}`);
console.log(`Region packages: ${regionPackages.join(", ")}`);
console.log(`Nested .pnpm IDs: ${nestedPackageIds.join(", ")}`);
console.log(`Nested packages: ${nestedPackageNames.join(", ")}`);
console.log(`Bare imports in region source files: ${[...importedPackages].sort().join(", ")}`);
console.log(`opentype.js: resolved as ${opentypeVersion}; declared by pptx-glimpse as ${glimpseManifest.dependencies["opentype.js"]}; its font loader signature is present in the bundled chunk`);
console.log(`Complete bundled package names (${packageInventory.length}): ${packageInventory.join(", ")}`);
console.log(`Inline code signatures: ${inlineChecks.map(([name, found]) => `${name}=${found ? "present" : "missing"}`).join(", ")}`);
console.log("Region paths:");
for (const sourcePath of regions) console.log(`  ${sourcePath}`);
console.log("Package source imports:");
for (const evidence of [...new Set(sourceImportEvidence)].sort()) console.log(`  ${evidence}`);

if (regions.length !== 21) throw new Error(`Expected 21 region directives; found ${regions.length}.`);
if (!bundle.includes("node_modules/.pnpm/@xmldom+xmldom@0.9.12/node_modules/@xmldom")) {
  throw new Error("Expected the @xmldom/xmldom@0.9.12 precompiled chunk ID.");
}
if (!bundle.includes("node_modules/.pnpm/rtf.js@3.0.9/node_modules/rtf.js")) {
  throw new Error("Expected the rtf.js@3.0.9 precompiled chunk ID.");
}
for (const [name, found] of inlineChecks) {
  if (!found) throw new Error(`Expected bundled ${name} implementation signature.`);
}
if (/resvg-wasm|@resvg\/resvg-wasm/i.test(bundle)) {
  throw new Error("Unexpected resvg-wasm code reference in the browser bundle.");
}
if (packageInventory.length !== 17) {
  throw new Error(`Expected 17 packages from included modules, inlined imports, and nested chunks; found ${packageInventory.length}.`);
}
