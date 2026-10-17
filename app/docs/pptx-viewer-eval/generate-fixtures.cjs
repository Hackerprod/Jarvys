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
    key: "image-png-data-url",
    title: "Embedded PNG from a data URL",
    slides: [{
      title: "The embedded PNG is visible",
      body: "This PNG reached create_presentation as an image data URL.",
      images: [{ dataUrl: pngDataUrl(), x: 6.6, y: 1.8, w: 4.7, h: 2.9, altText: "Blue sky, sun, and mountain landscape" }],
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
