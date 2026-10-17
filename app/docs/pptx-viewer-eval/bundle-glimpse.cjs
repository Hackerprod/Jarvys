"use strict";

const fs = require("node:fs");
const path = require("node:path");
const { pathToFileURL } = require("node:url");
const { gzipSync } = require("node:zlib");

const tempRoot = path.resolve(process.argv[2] || path.join(
  process.env.LOCALAPPDATA || process.env.TEMP || process.cwd(),
  "Temp",
  "jarvys-pptx-glimpse-browser-build",
));
const packageRoot = path.resolve(process.argv[3] || path.join(
  process.env.LOCALAPPDATA || process.env.TEMP || process.cwd(),
  "Temp",
  "jarvys-pptx-viewer-eval",
  "pptx-glimpse-5.3.1",
  "package",
));

async function main() {
  const viteEntry = path.join(tempRoot, "node_modules/vite/dist/node/index.js");
  const { build } = await import(pathToFileURL(viteEntry).href);
  const entry = path.join(tempRoot, "glimpse-browser-entry.js");
  const outputDirectory = path.join(tempRoot, "bundle-output");
  fs.writeFileSync(entry, 'export { convertPptxToSvg } from "pptx-glimpse";\n');

  await build({
    root: tempRoot,
    configFile: false,
    logLevel: "info",
    build: {
      outDir: outputDirectory,
      emptyOutDir: true,
      reportCompressedSize: false,
      lib: { entry, formats: ["es"], fileName: "browser" },
      rollupOptions: { output: { codeSplitting: false } },
    },
  });

  const bundleName = fs.readdirSync(outputDirectory).find((name) => name === "browser.mjs" || name === "browser.js.mjs" || name === "browser.js");
  if (!bundleName) throw new Error("Vite did not emit the single browser bundle.");
  const bundle = path.join(outputDirectory, bundleName);
  const target = path.join(packageRoot, "dist/browser.js");
  const source = fs.readFileSync(bundle, "utf8");
  const portableSource = source.replace(/^\/\/#region (.*)$/gm, (line, sourcePath) => {
    const normalizedPath = sourcePath.replaceAll("\\", "/");
    const nodeModulesMarker = "/node_modules/";
    const nodeModulesIndex = normalizedPath.lastIndexOf(nodeModulesMarker);
    return nodeModulesIndex < 0
      ? line
      : `//#region node_modules/${normalizedPath.slice(nodeModulesIndex + nodeModulesMarker.length)}`;
  });
  fs.writeFileSync(target, portableSource);
  const bytes = Buffer.from(portableSource, "utf8");
  process.stdout.write(`Vite browser bundle: ${bytes.length} B; ${gzipSync(bytes).length} B gzip\n`);
  process.stdout.write(`Installed in evaluation package: ${target}\n`);
}

main().catch((error) => {
  console.error(error && error.stack || error);
  process.exitCode = 1;
});
