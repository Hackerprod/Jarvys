"use strict";

const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const repo = path.resolve(__dirname, "../../..");
const moduleDirectory = path.join(repo, "app/app/src/main/assets/modules/presentation");
const fixtureDirectory = path.join(__dirname, "fixtures");
fs.mkdirSync(fixtureDirectory, { recursive: true });

const transparentPng = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/a1sAAAAASUVORK5CYII=";
const svgImage = Buffer.from(
  '<svg xmlns="http://www.w3.org/2000/svg" width="480" height="300" viewBox="0 0 480 300">' +
  '<rect width="480" height="300" rx="24" fill="#163B63"/>' +
  '<circle cx="340" cy="95" r="68" fill="#34B5A2"/>' +
  '<path d="M0 245L180 105l132 130 88-75 80 80v60H0z" fill="#F0B44D"/>' +
  '<text x="34" y="70" fill="white" font-family="Arial" font-size="30" font-weight="bold">Field team</text>' +
  '</svg>',
).toString("base64");

const canvasContext = new Proxy({
  fillText() {},
  measureText(value) { return { width: String(value).length * 8 }; },
}, { get(target, key) { return key in target ? target[key] : () => {}; } });
function makeCanvas() {
  return {
    width: 1280,
    height: 720,
    style: {},
    getContext() { return canvasContext; },
    toDataURL() { return "data:image/png;base64," + transparentPng; },
  };
}

globalThis.window = globalThis;
globalThis.document = {
  head: { appendChild() {} },
  body: { appendChild() {} },
  createElement(tag) { return tag === "canvas" ? makeCanvas() : {}; },
};
Object.defineProperty(globalThis, "navigator", {
  value: { userAgent: "pptx-viewer-eval-fixture-generator" }, configurable: true,
});
globalThis.JSZip = require(path.join(moduleDirectory, "vendor/jszip.min.js"));
globalThis.PptxDeckRenderer = {
  async renderFirstSlide(_bytes, canvas) {
    canvas.getContext("2d").fillText("Fixture thumbnail", 24, 40);
  },
};

const pptxgenFile = path.join(moduleDirectory, "vendor/pptxgen.min.js");
vm.runInThisContext(fs.readFileSync(pptxgenFile, "utf8"), {
  filename: pptxgenFile,
  importModuleDynamically: (specifier) => import(specifier),
});
const handlerFile = path.join(moduleDirectory, "logic/tools.js");
vm.runInThisContext(fs.readFileSync(handlerFile, "utf8"), { filename: handlerFile });

const presentations = [
  {
    key: "title-text",
    title: "Regional growth",
    slides: [{
      title: "Regional growth accelerated",
      body: "Revenue rose 22% this quarter, led by the North and South teams.",
      bullets: ["North finished at 42", "South finished at 57"],
    }],
  },
  {
    key: "bar-chart",
    title: "Regional sales chart",
    slides: [{
      title: "South leads regional sales",
      chart: {
        title: "Quarterly revenue",
        labels: ["North", "South", "West"],
        datasets: [{ name: "Revenue", values: [42, 57, 39], color: "2459D3" }],
      },
    }],
  },
  {
    key: "table",
    title: "Regional sales table",
    slides: [{
      title: "Two regions exceeded plan",
      table: {
        headers: ["Region", "Revenue", "Change"],
        rows: [["North", "$42M", "+18%"], ["South", "$57M", "+27%"], ["West", "$39M", "+8%"]],
      },
    }],
  },
  {
    key: "image-shape",
    title: "Field team image and shape",
    slides: [{
      title: "One team, three regions",
      body: "The regional teams shared one playbook.",
      shapes: [{ type: "roundRect", x: 0.9, y: 4.15, w: 3.0, h: 1.15, fillColor: "2459D3", text: "One playbook", fontSize: 22 }],
      images: [{ dataUrl: "data:image/svg+xml;base64," + svgImage, x: 6.6, y: 1.8, w: 4.7, h: 2.9, altText: "Mountain landscape with a team label" }],
    }],
  },
  {
    key: "theme-light-dark",
    title: "Light and dark theme slides",
    slides: [
      { title: "Invest in what works", body: "The lighter theme keeps detail clear and calm.", backgroundColor: "F7F9FC" },
      { title: "Grow the strongest regions", body: "Use the dark theme for a clear executive close.", backgroundColor: "14233B" },
      { title: "Carry momentum forward", body: "Keep the same palette and spacing across the deck.", backgroundColor: "E7F3F1" },
    ],
  },
];

let nextId = 0;
async function main() {
  for (const input of presentations) {
    const host = {
      newArtifactDeliveryId() { nextId += 1; return "eval-" + nextId; },
      artifactDeliverBytes(base64, name, artifactType, title, thumbnailBase64, thumbnailMimeType) {
        const bytes = Buffer.from(base64, "base64");
        const thumbnail = Buffer.from(thumbnailBase64, "base64");
        if (artifactType !== "application/vnd.openxmlformats-officedocument.presentationml.presentation" ||
            thumbnailMimeType !== "image/png" || thumbnail.readUInt32BE(0) !== 0x89504e47) {
          throw new Error("The production handler did not return a PPTX and PNG thumbnail.");
        }
        fs.writeFileSync(path.join(fixtureDirectory, input.key + ".pptx"), bytes);
        return JSON.stringify({ id: name });
      },
    };
    const result = await globalThis.createPresentation(input, host);
    if (!result.success) throw new Error("The production handler did not deliver " + input.key);
    process.stdout.write(input.key + " " + fs.statSync(path.join(fixtureDirectory, input.key + ".pptx")).size + " bytes\n");
  }
}

main().catch((error) => {
  console.error(error && error.stack || error);
  process.exitCode = 1;
});
