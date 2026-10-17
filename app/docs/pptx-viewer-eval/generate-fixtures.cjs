"use strict";

const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const zlib = require("node:zlib");

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

function pngDataUrl() {
  const width = 320;
  const height = 180;
  const pixels = Buffer.alloc(width * height * 4);
  const palette = {
    skyTop: [48, 119, 171],
    skyBottom: [181, 218, 232],
    sun: [255, 197, 84],
    farMountain: [36, 72, 96],
    nearMountain: [47, 115, 95],
    foreground: [45, 81, 69],
  };
  const inTriangle = (x, y, ax, ay, bx, by, cx, cy) => {
    const denominator = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy);
    const a = ((by - cy) * (x - cx) + (cx - bx) * (y - cy)) / denominator;
    const b = ((cy - ay) * (x - cx) + (ax - cx) * (y - cy)) / denominator;
    return a >= 0 && b >= 0 && a + b <= 1;
  };
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      const skyMix = y / height;
      let color = palette.skyTop.map((channel, index) => Math.round(channel * (1 - skyMix) + palette.skyBottom[index] * skyMix));
      if ((x - 245) ** 2 + (y - 47) ** 2 < 25 ** 2) color = palette.sun;
      if (inTriangle(x, y, 0, 155, 93, 58, 184, 155)) color = palette.farMountain;
      if (inTriangle(x, y, 104, 158, 221, 82, 320, 158)) color = palette.nearMountain;
      if (y >= 153) color = palette.foreground;
      const offset = (y * width + x) * 4;
      pixels[offset] = color[0];
      pixels[offset + 1] = color[1];
      pixels[offset + 2] = color[2];
      pixels[offset + 3] = 255;
    }
  }

  const crcTable = Array.from({ length: 256 }, (_, value) => {
    let crc = value;
    for (let bit = 0; bit < 8; bit += 1) crc = (crc & 1) ? 0xedb88320 ^ (crc >>> 1) : crc >>> 1;
    return crc >>> 0;
  });
  function crc32(buffer) {
    let crc = 0xffffffff;
    for (const byte of buffer) crc = crcTable[(crc ^ byte) & 0xff] ^ (crc >>> 8);
    return (crc ^ 0xffffffff) >>> 0;
  }
  function chunk(type, data) {
    const typeBytes = Buffer.from(type, "ascii");
    const body = Buffer.concat([typeBytes, data]);
    const length = Buffer.alloc(4);
    length.writeUInt32BE(data.length);
    const checksum = Buffer.alloc(4);
    checksum.writeUInt32BE(crc32(body));
    return Buffer.concat([length, body, checksum]);
  }

  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header[8] = 8;
  header[9] = 6;
  const scanlines = Buffer.alloc(height * (width * 4 + 1));
  for (let y = 0; y < height; y += 1) {
    const rowStart = y * (width * 4 + 1);
    scanlines[rowStart] = 0;
    pixels.copy(scanlines, rowStart + 1, y * width * 4, (y + 1) * width * 4);
  }
  const png = Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk("IHDR", header),
    chunk("IDAT", zlib.deflateSync(scanlines)),
    chunk("IEND", Buffer.alloc(0)),
  ]);
  return "data:image/png;base64," + png.toString("base64");
}

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
  createElement(tag) {
    if (tag === "canvas") return makeCanvas();
    if (tag === "img") {
      return {
        naturalWidth: 0,
        naturalHeight: 0,
        complete: false,
        onload: null,
        onerror: null,
        set src(value) {
          const isPng = value.startsWith("data:image/png;base64,");
          const svgPayload = value.slice(value.indexOf(",") + 1);
          const isSvg = value.startsWith("data:image/svg+xml") && decodeURIComponent(svgPayload).includes("<svg");
          queueMicrotask(() => {
            if (isPng || isSvg) {
              this.naturalWidth = isPng ? 320 : 1280;
              this.naturalHeight = isPng ? 180 : 720;
              this.complete = true;
              this.onload && this.onload();
            } else {
              this.complete = true;
              this.onerror && this.onerror();
            }
          });
        },
      };
    }
    return {};
  },
};
Object.defineProperty(globalThis, "navigator", {
  value: { userAgent: "pptx-viewer-eval-fixture-generator" }, configurable: true,
});
globalThis.JSZip = require(path.join(moduleDirectory, "vendor/jszip.min.js"));
class XmlElement {
  constructor(name) { this.localName = name.split(":").pop(); this.children = []; this.parentNode = null; this.value = ""; }
  getAttribute(name) { return this.attributes[name] || ""; }
  getAttributeNS(_namespace, name) { return this.attributes[name] || this.attributes[Object.keys(this.attributes).find((key) => key.endsWith(":" + name))] || ""; }
  get textContent() { return this.value + this.children.map((child) => child.textContent).join(""); }
}
function parseXml(source) {
  const root = new XmlElement("#document");
  root.attributes = {};
  const stack = [root];
  const tokens = source.match(/<!--[\s\S]*?-->|<\?[\s\S]*?\?>|<!\[CDATA\[[\s\S]*?\]\]>|<[^>]+>|[^<]+/g) || [];
  for (const token of tokens) {
    if (token.startsWith("<!--") || token.startsWith("<?")) continue;
    if (token.startsWith("<![CDATA[")) { stack[stack.length - 1].value += token.slice(9, -3); continue; }
    if (token.startsWith("</")) { stack.pop(); continue; }
    if (token.startsWith("<")) {
      const selfClosing = /\/\s*>$/.test(token);
      const inner = token.slice(1, selfClosing ? token.lastIndexOf("/") : -1).trim();
      const name = inner.match(/^([^\s/>]+)/)?.[1];
      if (!name) continue;
      const element = new XmlElement(name);
      element.attributes = {};
      const attributes = inner.slice(name.length);
      for (const match of attributes.matchAll(/([^\s=]+)\s*=\s*"([^"]*)"/g)) element.attributes[match[1]] = match[2];
      element.parentNode = stack[stack.length - 1];
      element.parentNode.children.push(element);
      if (!selfClosing) stack.push(element);
      continue;
    }
    stack[stack.length - 1].value += token;
  }
  return root;
}
globalThis.DOMParser = class { parseFromString(source) { return parseXml(source); } };
const glimpseContext = vm.createContext({});
const glimpseModule = new vm.SyntheticModule(["convertPptxToSvg"], function () {
  this.setExport("convertPptxToSvg", async (_bytes, options) => ({
    slides: options.slides.map((slide) => ({ svg: '<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" data-slide="' + slide + '"></svg>' })),
  }));
}, { context: glimpseContext });
const pptxgenFile = path.join(moduleDirectory, "vendor/pptxgen.min.js");
vm.runInThisContext(fs.readFileSync(pptxgenFile, "utf8"), {
  filename: pptxgenFile,
  importModuleDynamically: (specifier) => import(specifier),
});
const handlerFile = path.join(moduleDirectory, "logic/tools.js");
vm.runInThisContext(fs.readFileSync(handlerFile, "utf8"), {
  filename: handlerFile,
  importModuleDynamically(specifier) {
    if (specifier !== "../vendor/pptx-glimpse.bundle.js") throw new Error("Unexpected handler import " + specifier);
    return glimpseModule;
  },
});

const presentations = require("./composition-fixtures.cjs")(pngDataUrl);

let nextId = 0;
async function main() {
  await glimpseModule.link(() => {});
  await glimpseModule.evaluate();
  const reports = [];
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
    const { key, ...presentationInput } = input;
    const result = await globalThis.createPresentation(presentationInput, host);
    if (!result.success) throw new Error("The production handler did not deliver " + input.key);
    const content = JSON.parse(result.content);
    reports.push({ key, artifactId: content.artifactId, measurementReport: content.measurementReport });
    process.stdout.write(input.key + " " + fs.statSync(path.join(fixtureDirectory, input.key + ".pptx")).size + " bytes\n");
  }
  fs.writeFileSync(path.join(__dirname, "composition-measurement-reports.json"), JSON.stringify(reports, null, 2) + "\n");
}

main().catch((error) => {
  console.error(error && error.stack || error);
  process.exitCode = 1;
});
