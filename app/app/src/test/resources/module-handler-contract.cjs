"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const moduleDirectory = path.resolve(process.argv[2] || path.join(__dirname, "../../main/assets/modules/presentation"));
const manifest = JSON.parse(fs.readFileSync(path.join(moduleDirectory, "manifest.json"), "utf8"));
const declaration = manifest.tools[0];
const handlerFile = path.join(moduleDirectory, declaration.handler.split("#")[0]);
const thumbnailPng = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/a1sAAAAASUVORK5CYII=";
const canvases = [];
const thumbnailDrawCalls = [];
let thumbnailRendererInput = null;
let thumbnailRendererOptions = null;
const thumbnailSlideSvgs = [0, 1, 2, 3].map((index) =>
  '<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" data-slide="' + index + '"></svg>');

function decodeXmlText(value) {
  return value.replace(/&#x([0-9a-f]+);/gi, (_, code) => String.fromCodePoint(parseInt(code, 16)))
    .replace(/&#(\d+);/g, (_, code) => String.fromCodePoint(parseInt(code, 10)))
    .replace(/&lt;/g, "<").replace(/&gt;/g, ">")
    .replace(/&quot;/g, "\"").replace(/&apos;/g, "'").replace(/&amp;/g, "&");
}

class XmlElement {
  constructor(name, attributes = {}) {
    this.tagName = name;
    this.localName = name.split(":").pop();
    this.attributes = attributes;
    this.children = [];
    this.parentNode = null;
    this.directText = "";
    this.order = 0;
  }
  getAttribute(name) { return this.attributes[name] || ""; }
  getAttributeNS(_namespace, name) {
    return this.attributes[Object.keys(this.attributes).find((key) => key.endsWith(":" + name))] || this.attributes[name] || "";
  }
  get textContent() { return this.directText + this.children.map((child) => child.textContent).join(""); }
  getElementsByTagNameNS(_namespace, name) {
    const found = [];
    const visit = (node) => node.children.forEach((child) => {
      if (child.localName === name) found.push(child);
      visit(child);
    });
    visit(this);
    return found;
  }
  getElementsByTagName(name) { return this.getElementsByTagNameNS("*", name); }
  compareDocumentPosition(other) { return this.order > other.order ? 2 : 4; }
}

function parseXml(source) {
  const root = new XmlElement("#document");
  const stack = [root];
  let order = 0;
  const tokens = source.match(/<!--[\s\S]*?-->|<\?[\s\S]*?\?>|<!\[CDATA\[[\s\S]*?\]\]>|<[^>]+>|[^<]+/g) || [];
  tokens.forEach((token) => {
    if (token.startsWith("<!--") || token.startsWith("<?") || token.startsWith("<!DOCTYPE")) return;
    if (token.startsWith("<![CDATA[")) {
      stack[stack.length - 1].directText += token.slice(9, -3);
      return;
    }
    if (token.startsWith("</")) { stack.pop(); return; }
    if (token.startsWith("<")) {
      const selfClosing = /\/\s*>$/.test(token);
      const inner = token.slice(1, selfClosing ? token.lastIndexOf("/") : -1).trim();
      const name = inner.match(/^([^\s/>]+)/)?.[1];
      if (!name) return;
      const attributes = {};
      const attributeSource = inner.slice(name.length);
      const pattern = /([^\s=]+)\s*=\s*"([^"]*)"/g;
      let match;
      while ((match = pattern.exec(attributeSource))) attributes[match[1]] = decodeXmlText(match[2]);
      const element = new XmlElement(name, attributes);
      element.order = ++order;
      element.parentNode = stack[stack.length - 1];
      element.parentNode.children.push(element);
      if (!selfClosing) stack.push(element);
      return;
    }
    stack[stack.length - 1].directText += decodeXmlText(token);
  });
  if (stack.length !== 1) throw new Error("Malformed OOXML document in test fixture");
  return root;
}

function descendants(node) {
  return node.children.reduce((found, child) => found.concat(child, descendants(child)), []);
}

function assertSlideElementsWithinHeight(xml, slideNumber) {
  const root = parseXml(xml);
  const emuTolerance = 1;
  const slideWidthEmu = 12192000;
  const slideHeightEmu = 6858000;
  const boxes = descendants(root).filter((node) => ["sp", "graphicFrame", "pic", "cxnSp"].includes(node.localName)).map((element) => {
    const transform = descendants(element).find((node) => node.localName === "xfrm");
    if (!transform) return null;
    const transformNodes = descendants(transform);
    const offset = transformNodes.find((node) => node.localName === "off");
    const extent = transformNodes.find((node) => node.localName === "ext");
    if (!offset || !extent) return null;
    const nonVisual = descendants(element).find((node) => node.localName === "cNvPr");
    const text = descendants(element).filter((node) => node.localName === "t").map((node) => node.directText).join("");
    const presetGeometry = descendants(element).find((node) => node.localName === "prstGeom")?.getAttribute("prst");
    const box = {
      element,
      name: nonVisual?.getAttribute("name") || element.localName,
      text,
      isLine: element.localName === "cxnSp" || presetGeometry === "line",
      x: Number(offset.getAttribute("x")),
      y: Number(offset.getAttribute("y")),
      w: Number(extent.getAttribute("cx")),
      h: Number(extent.getAttribute("cy")),
    };
    assert.ok(box.x >= -emuTolerance && box.y >= -emuTolerance,
      "slide " + slideNumber + " element " + box.name + " starts outside the slide");
    assert.ok(box.w > 0 && box.h > 0 || box.isLine && box.w >= 0 && box.h >= 0 && (box.w > 0 || box.h > 0),
      "slide " + slideNumber + " element " + box.name + " has an invalid zero-area transform");
    assert.ok(box.x + box.w <= slideWidthEmu + emuTolerance,
      "slide " + slideNumber + " element " + box.name + " exceeds the slide width");
    assert.ok(box.y + box.h <= slideHeightEmu + emuTolerance,
      "slide " + slideNumber + " element " + box.name + " exceeds the slide height");
    return box;
  }).filter(Boolean);

  for (let firstIndex = 0; firstIndex < boxes.length; firstIndex += 1) {
    for (let secondIndex = firstIndex + 1; secondIndex < boxes.length; secondIndex += 1) {
      const first = boxes[firstIndex];
      const second = boxes[secondIndex];
      const overlapWidth = Math.min(first.x + first.w, second.x + second.w) - Math.max(first.x, second.x);
      const overlapHeight = Math.min(first.y + first.h, second.y + second.h) - Math.max(first.y, second.y);
      assert.ok(overlapWidth <= emuTolerance || overlapHeight <= emuTolerance,
        "slide " + slideNumber + " elements " + first.name + " and " + second.name + " overlap");
    }
  }
  return boxes;
}

function makeCanvas() {
  const canvas = {
    width: 1280,
    height: 720,
    clientWidth: 1280,
    clientHeight: 720,
    style: {},
    setAttribute() {},
    addEventListener() {},
    removeEventListener() {},
    getBoundingClientRect() { return { left: 0, top: 0, width: this.width, height: this.height }; },
    toDataURL() { return "data:image/png;base64," + thumbnailPng; },
  };
  canvases.push(canvas);
  const context = new Proxy({
    canvas,
    measureText(text) {
      // Node has no browser font rasterizer here. This deterministic width-only double exercises report structure;
      // Android WebView instrumentation checks the same handler against real canvas font metrics.
      const match = String(context.font || "").match(/([\d.]+)px/);
      const fontPixels = match ? Number(match[1]) : 16;
      return { width: String(text).length * fontPixels * 0.52 };
    },
    drawImage(image, ...args) {
      if (image.tagName === "IMG") thumbnailDrawCalls.push({ image, args, canvas });
    },
    fillText() {},
    fill() {},
    getLineDash() { return []; },
    getTransform() { return { a: 1, b: 0, c: 0, d: 1, e: 0, f: 0 }; },
  }, {
    get(target, key) { return key in target ? target[key] : () => {}; },
    set(target, key, value) { target[key] = value; return true; },
  });
  context.getContext = () => context;
  canvas.getContext = () => context;
  return canvas;
}

const document = {
  body: { appendChild() {} },
  head: { appendChild() {} },
  createElement(tag) {
    if (tag === "canvas") return makeCanvas();
    if (tag === "img") {
      let source = "";
      return {
        tagName: "IMG",
        naturalWidth: 0,
        naturalHeight: 0,
        complete: false,
        onload: null,
        onerror: null,
        set src(value) {
          source = value;
          const knownPng = value === "data:image/png;base64," + thumbnailPng;
          const svgPayload = value.slice(value.indexOf(",") + 1);
          const svgText = value.startsWith("data:image/svg+xml")
            ? (value.slice(0, value.indexOf(",")).includes("base64")
              ? Buffer.from(svgPayload, "base64").toString("utf8")
              : decodeURIComponent(svgPayload))
            : "";
          const decodedSvg = svgText.includes("<svg");
          queueMicrotask(() => {
            if (knownPng || decodedSvg) {
              this.naturalWidth = knownPng ? 1 : 1280;
              this.naturalHeight = knownPng ? 1 : 720;
              this.complete = true;
              this.onload && this.onload();
            } else {
              this.complete = true;
              this.onerror && this.onerror();
            }
          });
        },
        get src() { return source; },
      };
    }
    return { setAttribute() {}, addEventListener() {} };
  },
};
globalThis.window = globalThis;
globalThis.document = document;
globalThis.DOMParser = class { parseFromString(source) { return parseXml(source); } };
Object.defineProperty(globalThis, "navigator", {
  value: { userAgent: "node-module-contract" },
  configurable: true,
});
globalThis.devicePixelRatio = 1;
globalThis.requestAnimationFrame = (callback) => setTimeout(() => callback(Date.now()), 0);
globalThis.cancelAnimationFrame = clearTimeout;
globalThis.getComputedStyle = () => ({ getPropertyValue: () => "" });

const deliveries = [];
let deliveryOrdinal = 0;
const host = {
  newArtifactDeliveryId() {
    deliveryOrdinal += 1;
    return "00000000-0000-4000-8000-" + String(deliveryOrdinal).padStart(12, "0");
  },
  artifactDeliverBytes(base64, name, artifactType, title, thumbnailBase64, thumbnailMimeType, deliveryId) {
    const delivery = {
      bytes: Buffer.from(base64, "base64"),
      name,
      artifactType,
      title,
      thumbnail: Buffer.from(thumbnailBase64, "base64"),
      thumbnailMimeType,
      deliveryId,
    };
    deliveries.push(delivery);
    return JSON.stringify({ id: "node-contract-artifact" });
  },
};

async function main() {
  let argumentsObject;
  let handlerDynamicImport;
  let handlerSource;
  if (manifest.id === "com.jarvys.module.chart") {
    const chartBundle = require(path.join(moduleDirectory, "vendor/chart.umd.min.js"));
    assert.equal(typeof chartBundle, "function");
    globalThis.Chart = class {
      constructor(context, config) {
        assert.ok(context && context.canvas);
        assert.equal(context.canvas.width, 1280);
        assert.equal(context.canvas.height, 720);
        assert.equal(config.type, "bar");
        assert.equal(config.options.devicePixelRatio, 1);
        this.context = context;
      }
      update() {}
      draw() {}
    };
    argumentsObject = {
      title: "Quarterly sales",
      chartType: "bar",
      labels: ["Q1", "Q2"],
      datasets: [{ label: "Sales", data: [4, 7] }],
    };
  } else if (manifest.id === "com.jarvys.module.presentation") {
    const skillMarkdown = fs.readFileSync(path.join(moduleDirectory, manifest.skills[0]));
    assert.ok(skillMarkdown.byteLength < 256 * 1024, "module skill must fit the shared 256 KiB skill-reader limit");
    globalThis.JSZip = require(path.join(moduleDirectory, "vendor/jszip.min.js"));
    const glimpseContext = vm.createContext({});
    const glimpseModule = new vm.SyntheticModule(["convertPptxToSvg"], function () {
      this.setExport("convertPptxToSvg", async (bytes, options) => {
        thumbnailRendererInput = Buffer.from(bytes);
        thumbnailRendererOptions = options;
        return { slides: thumbnailSlideSvgs.map((svg) => ({ svg })) };
      });
    }, { context: glimpseContext });
    await glimpseModule.link(() => {});
    await glimpseModule.evaluate();
    handlerDynamicImport = (specifier) => {
      assert.equal(specifier, "../vendor/pptx-glimpse.bundle.js",
        "the thumbnail must use the same bundled PptxGlimpse module as the viewer");
      return glimpseModule;
    };
    const generatorFile = path.join(moduleDirectory, "vendor/pptxgen.min.js");
    vm.runInThisContext(fs.readFileSync(generatorFile, "utf8"), {
      filename: generatorFile,
      importModuleDynamically: (specifier) => import(specifier),
    });
    argumentsObject = {
      title: "Quarterly review",
      theme: { fonts: { headFontFace: "Arial", bodyFontFace: "Arial" }, colors: { accent1: "2459D3", accent2: "18A999" } },
      slides: [
        {
          notes: "Cover slide notes",
          elements: [
            { type: "shape", shape: "ellipse", x: 10.0, y: 0.5, w: 2.2, h: 2.2, fill: { color: "DDEBFF" }, line: { color: "DDEBFF" } },
            { type: "text", runs: [{ text: "Regional ", bold: true, color: "18253A" }, { text: "outlook", italic: true, color: "2459D3" }], x: 0.8, y: 2.0, w: 8.5, h: 1.2, fontSize: 34, fontFace: "Arial", color: "18253A", margin: 0 },
          ],
        },
        {
          elements: [
            { type: "text", text: "A shared field playbook", x: 0.8, y: 1.0, w: 5.5, h: 0.8, fontSize: 27, fontFace: "Arial", color: "18253A", bold: true, margin: 0 },
            { type: "text", text: "Local teams adapted the same approach to regional demand.", x: 0.8, y: 2.0, w: 5.5, h: 1.8, fontSize: 19, fontFace: "Arial", color: "344054", margin: 0 },
            { type: "image", dataUrl: "data:image/png;base64," + thumbnailPng, x: 6.8, y: 1.0, w: 5.4, h: 4.2, sizing: "contain", altText: "Embedded PNG" },
          ],
        },
        {
          elements: [
            { type: "chart", chartType: "bar", labels: ["North", "South", "West"], datasets: [{ name: "Revenue", values: [42, 57, 39], color: "2459D3" }], x: 0.8, y: 1.5, w: 6.0, h: 4.9, options: { title: "Quarterly revenue", showTitle: true, showValue: true, showLegend: false } },
            { type: "text", text: "South led total revenue", x: 7.4, y: 1.6, w: 4.6, h: 0.8, fontSize: 20, fontFace: "Arial", color: "18253A", bold: true, margin: 0 },
            { type: "text", text: "North delivered the next highest result", x: 7.4, y: 3.0, w: 4.6, h: 0.8, fontSize: 20, fontFace: "Arial", color: "18253A", margin: 0 },
            { type: "text", text: "West remained close to the prior quarter", x: 7.4, y: 4.4, w: 4.6, h: 0.8, fontSize: 20, fontFace: "Arial", color: "18253A", margin: 0 },
          ],
        },
        {
          notes: ["Revenue sources: regional operating report."],
          elements: [
            { type: "text", text: "Every region improved while operating costs stayed within plan", x: 0.8, y: 0.4, w: 11.4, h: 1.3, fontSize: 29, fontFace: "Arial", color: "18253A", bold: true, margin: 0 },
            { type: "text", text: "Quarterly operating summary", x: 0.8, y: 1.85, w: 8.0, h: 0.5, fontSize: 18, fontFace: "Arial", color: "344054", margin: 0 },
            { type: "table", rows: [["Region", "Revenue", "Change"], ["North", "$42M", "+18%"], ["South", "$57M", "+27%"], ["West", "$39M", "+8%"]], x: 0.8, y: 2.6, w: 11.5, h: 3.6, colW: [4.2, 3.5, 3.8], fontSize: 16, fontFace: "Arial", color: "18253A", margin: 0.08 },
          ],
        },
      ],
    };  } else {
    throw new Error("Unexpected bundled module id: " + manifest.id);
  }

  handlerSource = fs.readFileSync(handlerFile, "utf8");
  const handlerOptions = { filename: handlerFile };
  if (handlerDynamicImport) handlerOptions.importModuleDynamically = handlerDynamicImport;
  new vm.Script(handlerSource, handlerOptions).runInThisContext();
  const exportName = declaration.handler.split("#")[1];
  assert.equal(typeof globalThis[exportName], "function");
  const result = await globalThis[exportName](argumentsObject, host);
  assert.equal(result.success, true);
  assert.equal(result.artifactType, manifest.artifactTypes[0].type);
  assert.equal(deliveries.length, 1);

  const delivery = deliveries[0];
  assert.equal(delivery.artifactType, manifest.artifactTypes[0].type);
  assert.equal(delivery.thumbnailMimeType, "image/png");
  assert.match(delivery.deliveryId, /^[0-9a-f-]{36}$/);
  assert.deepEqual(Array.from(delivery.thumbnail.subarray(0, 4)), [0x89, 0x50, 0x4e, 0x47]);
  assert.equal(canvases[canvases.length - 1].width, 1280);
  assert.equal(canvases[canvases.length - 1].height, 720);

  if (manifest.id === "com.jarvys.module.chart") {
    const artifact = JSON.parse(delivery.bytes.toString("utf8"));
    assert.equal(artifact.title, "Quarterly sales");
    assert.equal(artifact.chartType, "bar");
    assert.deepEqual(artifact.data.labels, ["Q1", "Q2"]);
  } else {
    assert.equal(delivery.name, "Quarterly review.pptx");
    const response = JSON.parse(result.content);
    assert.equal(response.artifactId, "node-contract-artifact");
    assert.deepEqual(response.layout, { name: "LAYOUT_WIDE", width_in: 13.333333333333334, height_in: 7.5 });
    assert.equal(response.measurementReport.slides.length, 4);
    assert.equal(response.measurementReport.slides[0].elements.length, 2);
    assert.deepEqual(thumbnailRendererInput, delivery.bytes,
      "the generated PPTX bytes must reach the same PptxGlimpse renderer used by the viewer");
    assert.deepEqual(thumbnailRendererOptions, { textOutput: "text", width: 1280, slides: [1] },
      "the host artifact contract keeps the existing first-slide PNG thumbnail");
    assert.equal(thumbnailDrawCalls.length, 1);
    assert.deepEqual(thumbnailDrawCalls[0].args, [0, 0, 1280, 720]);

    const zip = await globalThis.JSZip.loadAsync(delivery.bytes);
    assert.ok(zip.file("[Content_Types].xml"));
    const expectedSlides = argumentsObject.slides;
    for (let slideNumber = 1; slideNumber <= expectedSlides.length; slideNumber += 1) {
      const slideXml = await zip.file("ppt/slides/slide" + slideNumber + ".xml").async("string");
      const boxes = assertSlideElementsWithinHeight(slideXml, slideNumber);
      const reportSlide = response.measurementReport.slides[slideNumber - 1];
      assert.equal(reportSlide.elements.length, expectedSlides[slideNumber - 1].elements.length);
      expectedSlides[slideNumber - 1].elements.forEach((element, elementIndex) => {
        const measured = reportSlide.elements[elementIndex];
        assert.equal(measured.index, elementIndex);
        assert.equal(measured.type, element.type);
        assert.equal(measured.box.x, boxes[elementIndex].x / 914400);
        assert.equal(measured.box.y, boxes[elementIndex].y / 914400);
        assert.equal(measured.box.x, element.x === undefined ? measured.box.x : element.x);
        assert.equal(measured.box.y, element.y);
        if (element.type !== "table") {
          assert.equal(measured.box.w, element.w);
          assert.equal(measured.box.h, element.h);
        } else {
          assert.equal(measured.box.w, element.w);
        }
        assert.equal(measured.outside_slide, false);
      });
      assert.deepEqual(reportSlide.outside_bounds, [], "accepted explicit elements stay within slide bounds");
      assert.deepEqual(reportSlide.overlaps, [], "the four authored examples have no intersecting boxes");
    }

    const coverXml = await zip.file("ppt/slides/slide1.xml").async("string");
    assert.match(coverXml, /Regional/);
    assert.match(coverXml, /outlook/);
    assert.match(coverXml, /<a:prstGeom prst="ellipse"/);
    assert.match(coverXml, /<a:rPr[^>]*b="1"/);
    assert.match(coverXml, /<a:rPr[^>]*i="1"/);
    assert.equal(response.measurementReport.slides[0].elements[1].contrast.known, true);
    assert.ok(response.measurementReport.slides[0].minimum_font_size_pt > 0);

    const imageXml = await zip.file("ppt/slides/slide2.xml").async("string");
    assert.match(imageXml, /shared field playbook/i);
    assert.ok(Object.keys(zip.files).some((name) => name.startsWith("ppt/media/") && name.endsWith(".png")),
      "a PNG data URL becomes an embedded image part");

    const chartXml = await zip.file("ppt/charts/chart1.xml").async("string");
    assert.match(chartXml, /Quarterly revenue/);
    assert.match(chartXml, /<c:v>North<\/c:v>/);
    assert.match(chartXml, /<c:v>57<\/c:v>/);
    const tableSlideXml = await zip.file("ppt/slides/slide4.xml").async("string");
    assert.equal((tableSlideXml.match(/<a:tbl>/g) || []).length, 1, "table output remains native OOXML");
    ["Every region improved while operating costs stayed within plan", "Quarterly operating summary", "Revenue", "$57M"].forEach((value) => {
      assert.ok(tableSlideXml.includes(value), "the authored slide includes " + value);
    });
    assert.ok(Object.keys(zip.files).some((name) => name.startsWith("ppt/notesSlides/notesSlide") && name.endsWith(".xml")),
      "slide notes are preserved in the PPTX package");

    const ownerElements = [
      { type: "text", text: "Regional growth accelerated this quarter", x: 0.8, y: 0.3, w: 6.0, h: 0.55, fontSize: 30, fontFace: "Arial", color: "000000", margin: 0 },
      { type: "text", text: "Quarterly outlook", x: 0.8, y: 0.7, w: 5.0, h: 0.6, fontSize: 18, fontFace: "Arial", color: "000000", margin: 0 },
      ...["North finished at 42", "South finished at 57", "West remained stable", "All regions exceeded plan"].map((text, index) => ({
        type: "text", text, x: 0.8, y: 2.0 + index * 0.8, w: 7.5, h: 0.5, fontSize: 18, fontFace: "Arial", color: "000000", bullet: true, margin: 0,
      })),
    ];
    const ownerResult = await globalThis.createPresentation({ title: "Owner overlap regression", slides: [{ elements: ownerElements }] }, host);
    assert.equal(ownerResult.success, true, "overflow and overlap are feedback, not delivery errors");
    const ownerReport = JSON.parse(ownerResult.content).measurementReport.slides[0];
    assert.equal(ownerReport.elements[0].text.overflow, true);
    assert.equal(ownerReport.elements[0].text.line_count, 2, "the owner regression title wraps onto two lines");
    assert.ok(ownerReport.elements[0].text.needed_h > ownerReport.elements[0].text.box_h);
    assert.deepEqual(ownerReport.overlaps.map((entry) => entry.elements), [[0, 1]]);
    assert.equal(ownerReport.elements[0].contrast.known, true);
    assert.ok(ownerReport.minimum_font_size_pt > 0);

    const correctedElements = ownerElements.map((element, index) => index === 0
      ? { ...element, w: 11.5, h: 1.1 }
      : index === 1 ? { ...element, y: 1.5 }
        : { ...element, y: 2.7 + (index - 2) * 0.8 });
    const correctedResult = await globalThis.createPresentation({ title: "Owner corrected layout", slides: [{ elements: correctedElements }] }, host);
    const correctedReport = JSON.parse(correctedResult.content).measurementReport.slides[0];
    assert.equal(correctedReport.elements[0].text.overflow, false);
    assert.deepEqual(correctedReport.overlaps, []);

    const generatedOutOfBounds = await globalThis.createPresentation({
      title: "Generated table crosses slide edge",
      slides: [{ elements: [{ type: "table", rows: [["A"], ["B"]], x: 0.8, y: 7.1, w: 4.0, rowH: [1.0, 1.0] }] }],
    }, host);
    const generatedBoundsReport = JSON.parse(generatedOutOfBounds.content).measurementReport.slides[0];
    assert.equal(generatedBoundsReport.elements[0].outside_slide, true);
    assert.equal(generatedBoundsReport.outside_bounds.length, 1);

    const beforeInvalidInputs = deliveries.length;
    const invalidCases = [
      [{ type: "audio", x: 0, y: 0, w: 1, h: 1 }, "type"],
      [{ type: "text", text: "Unknown prop", x: 0, y: 0, w: 1, h: 1, autoPlace: true }, "autoPlace"],
      [{ type: "text", text: "Non-finite", x: 0, y: 0, w: Infinity, h: 1 }, "w"],
      [{ type: "text", text: "Negative", x: 0, y: 0, w: -1, h: 1 }, "w"],
      [{ type: "text", text: "Negative height", x: 0, y: 0, w: 1, h: -1 }, "h"],
      [{ type: "text", text: "Outside", x: 13, y: 0, w: 1, h: 1 }, "bounds"],
      [{ type: "chart", chartType: "bar", labels: [], datasets: [], x: 0, y: 0, w: 2, h: 2 }, "labels"],
      [{ type: "chart", chartType: "bar", labels: ["North"], datasets: [], x: 0, y: 0, w: 2, h: 2 }, "datasets"],
      [{ type: "chart", chartType: "bar", labels: ["North", "South"], datasets: [{ name: "Sales", values: [1] }], x: 0, y: 0, w: 2, h: 2 }, "values"],
      [{ type: "chart", chartType: "bar", labels: ["North"], datasets: [{ name: "Sales", values: [NaN] }], x: 0, y: 0, w: 2, h: 2 }, "values[0]"],
      [{ type: "image", dataUrl: "https://example.invalid/image.png", x: 0, y: 0, w: 2, h: 2 }, "dataUrl"],
      [{ type: "image", dataUrl: "data:image/png;base64,not-an-image", x: 0, y: 0, w: 2, h: 2 }, "dataUrl"],
      [{ type: "table", rows: [["A", "B"], ["only one"]], x: 0, y: 0, w: 3 }, "rows[1]"]
    ];
    for (const [element, field] of invalidCases) {
      await assert.rejects(
        globalThis.createPresentation({ title: "Invalid input", slides: [{ elements: [element] }] }, host),
        (error) => error.message.includes("Slide 1") && error.message.includes("element 0") && error.message.includes(field),
        "invalid " + element.type + " input reports slide, element and field " + field,
      );
    }
    assert.equal(deliveries.length, beforeInvalidInputs, "invalid input never delivers a partial presentation");

    const svgResult = await globalThis.createPresentation({
      title: "SVG support notice",
      slides: [{ elements: [{ type: "image", dataUrl: "data:image/svg+xml;base64," + Buffer.from("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"2\" height=\"2\"></svg>").toString("base64"), x: 1, y: 1, w: 2, h: 2 }] }],
    }, host);
    const svgReport = JSON.parse(svgResult.content).measurementReport;
    assert.equal(svgReport.warnings[0].field, "dataUrl");
    assert.match(svgReport.warnings[0].fact, /SVG/);
    const viewerHtml = fs.readFileSync(path.join(moduleDirectory, "viewer/presentation.html"), "utf8");
    assert.match(viewerHtml, /grid-template-rows:\s*minmax\(0,\s*1fr\)\s+auto\s+auto/);
    assert.doesNotMatch(viewerHtml, /<h1\b/, "the host already provides the presentation title");
    assert.ok(viewerHtml.indexOf("class=\"stage\"") < viewerHtml.indexOf("<nav"));
    assert.doesNotMatch(viewerHtml.match(/nav\s*\{[^}]*\}/)?.[0] || "", /position\s*:\s*absolute/);
    assert.match(viewerHtml, /<script src="presentation\.js"><\/script>/);
    assert.match(viewerHtml, /<p class="loading" id="loading">Loading presentation…<\/p>/,
      "the viewer must show its loading state before presentation.js runs");
    const viewerJs = fs.readFileSync(path.join(moduleDirectory, "viewer/presentation.js"), "utf8");
    assert.match(viewerJs, /loadingPresentation: "Cargando presentación…"/);
    assert.match(viewerJs, /loadingPresentation: "Loading presentation…"/);
    assert.match(viewerJs, /stage\.setAttribute\("aria-label", labels\.stage\)/);
    assert.match(viewerHtml, /window\.addEventListener\("error"[\s\S]*window\.addEventListener\("unhandledrejection"/,
      "the standalone HTML fallback must report script/global and unhandled promise failures");
    assert.doesNotMatch(viewerHtml, /sizeViewport|window\.innerHeight/,
      "the viewer layout must use its host's measured WebView bounds");
    assert.doesNotMatch(viewerHtml, /setTimeout\([^]*?10000/,
      "a slow render must keep its loading state instead of becoming an error");
    assert.match(viewerHtml, /if \(ready \|\| failureShown\) return/,
      "global errors after a successful render must not cover the working deck");
    assert.match(viewerHtml, /window\.__presentationViewerReady = function \(\)/,
      "the viewer script must report when the first slide has rendered");
    assert.match(viewerJs, /slides:\s*\[index \+ 1\]/,
      "the viewer must convert only the selected slide");
    assert.match(viewerJs, /slideCache\.set\(index, result\.svg\)/,
      "the viewer must retain SVGs it has already converted");
    assert.match(viewerJs, /getElementsByTagNameNS/);
    assert.match(viewerJs, /"sldId"/,
      "the viewer must count slides from the PPTX manifest without converting each slide");
    assert.match(viewerHtml, /data-error-es="No se pudo abrir la presentación\./);
    assert.match(viewerHtml, /data-error-en="The presentation could not be opened\./);
    assert.doesNotMatch(viewerHtml, /deck-renderer\.js/, "the user-visible viewer must have one renderer");
    assert.equal(fs.existsSync(path.join(moduleDirectory, "viewer/deck-renderer.js")), false,
      "the separate thumbnail renderer asset must be removed");
    assert.doesNotMatch(handlerSource, /PptxDeckRenderer|deck-renderer\.js/,
      "the handler must load no separate thumbnail renderer");

    const fallbackSource = viewerHtml.match(/<script>([\s\S]*?)<\/script>/)?.[1];
    assert.ok(fallbackSource, "the page must carry its fallback independently of presentation.js and PptxGlimpse");
    function runStandaloneFallback(language = "es-ES") {
      const listeners = {};
      const fallbackNodes = {
        status: {
          hidden: true,
          textContent: "",
          getAttribute(name) {
            return name === "data-error-es"
              ? "No se pudo abrir la presentación. Vuelve a intentarlo o abre el archivo en una aplicación compatible."
              : "The presentation could not be opened. Try again or open the file in a compatible app.";
          },
        },
        counter: { textContent: "Cargando…" },
      };
      const fallbackWindow = {
        addEventListener(name, callback) { listeners[name] = callback; },
      };
      vm.runInNewContext(fallbackSource, {
        window: fallbackWindow,
        document: { getElementById: (id) => fallbackNodes[id] || null },
        navigator: { language },
      });
      return { fallbackNodes, fallbackWindow, listeners };
    }

    const scriptFailureFallback = runStandaloneFallback();
    scriptFailureFallback.listeners.error({
      target: { tagName: "IMG", getAttribute: () => "" },
    });
    assert.equal(scriptFailureFallback.fallbackNodes.status.hidden, true,
      "an unrelated image failure must not report a presentation load failure");
    scriptFailureFallback.listeners.error({
      target: { tagName: "SCRIPT", getAttribute: () => "presentation.js" },
    });
    assert.equal(scriptFailureFallback.fallbackNodes.status.hidden, false,
      "a failed presentation.js load must display the standalone alert");
    assert.match(scriptFailureFallback.fallbackNodes.status.textContent, /No se pudo abrir la presentación/);
    assert.equal(scriptFailureFallback.fallbackNodes.counter.textContent, "");

    const rejectionFallback = runStandaloneFallback("en-US");
    rejectionFallback.listeners.unhandledrejection({ reason: new Error("fixture-rejection") });
    assert.equal(rejectionFallback.fallbackNodes.status.hidden, false,
      "an unhandled rejection must display the standalone alert");
    assert.match(rejectionFallback.fallbackNodes.status.textContent, /The presentation could not be opened/);

    const completedFallback = runStandaloneFallback();
    completedFallback.fallbackWindow.__presentationViewerReady();
    completedFallback.listeners.error({ target: completedFallback.fallbackWindow });
    assert.equal(completedFallback.fallbackNodes.status.hidden, true,
      "a later global error must not cover the successfully rendered presentation");

    const viewerScript = path.join(moduleDirectory, "viewer/presentation.js");
    const viewerSource = fs.readFileSync(viewerScript, "utf8");
    let failSlideImageDecode = false;
    let requestedArtifactUrl = "";
    let convertedBytes = null;
    let convertedOptions = null;
    let renderedSlideIndices = [];
    let viewerFallbackReadyCalls = 0;

    function setupViewerNodes() {
      const viewerNodes = {
        stage: {
          clientWidth: 320,
          clientHeight: 210,
          children: [],
          setAttribute(name, value) { this[name] = value; },
          replaceChildren(...children) { this.children = children; },
        },
        loading: { hidden: false, textContent: "" },
        navigation: { setAttribute(name, value) { this[name] = value; } },
        counter: { textContent: "" },
        previous: { disabled: true, textContent: "", addEventListener(name, handler) { this[name] = handler; }, click() { if (!this.disabled) this.clickHandler && this.clickHandler(); } },
        next: { disabled: true, textContent: "", addEventListener(name, handler) { this.clickHandler = handler; }, click() { if (!this.disabled) this.clickHandler(); } },
        status: { hidden: true, textContent: "" },
      };
      viewerNodes.previous.addEventListener = function (name, handler) { this.clickHandler = handler; };
      viewerNodes.stage.getBoundingClientRect = () => ({ width: 320, height: 210 });
      document.documentElement = { lang: "es" };
      document.title = "";
      document.getElementById = (id) => viewerNodes[id];
      document.createElement = (tag) => {
        assert.equal(tag, "img");
        const image = {
          id: "",
          className: "",
          alt: "",
          style: {},
          naturalWidth: 1280,
          naturalHeight: 720,
          complete: false,
          onload: null,
          onerror: null,
          _src: "",
          set src(value) {
            this._src = value;
            const slideMatch = decodeURIComponent(value.slice(value.indexOf(",") + 1)).match(/data-slide="(\d+)"/);
            if (slideMatch) renderedSlideIndices.push(Number(slideMatch[1]));
            queueMicrotask(() => {
              this.complete = !failSlideImageDecode;
              if (failSlideImageDecode) this.onerror && this.onerror();
              else this.onload && this.onload();
            });
          },
          get src() { return this._src; },
          getAttribute(name) { return name === "src" ? this.src : null; },
        };
        return image;
      };
      globalThis.getComputedStyle = () => ({
        paddingLeft: "12px", paddingRight: "12px", paddingTop: "12px", paddingBottom: "12px",
      });
      return viewerNodes;
    }

    async function runViewer(converter, language = "es-ES", countedSlideCount = 4, failFetch = false) {
      const viewerNodes = setupViewerNodes();
      Object.defineProperty(globalThis, "navigator", {
        value: { userAgent: "node-module-contract", language }, configurable: true,
      });
      globalThis.JarvysArtifact = { url: "https://modules.jarvys.invalid/module-artifacts/fixture" };
      globalThis.DOMParser = class {
        parseFromString(xml) {
          return {
            getElementsByTagName() { return []; },
            getElementsByTagNameNS(namespace, name) {
              assert.equal(name, "sldId");
              return Array.from({ length: countedSlideCount });
            },
          };
        }
      };
      viewerFallbackReadyCalls = 0;
      globalThis.__presentationViewerReady = () => { viewerFallbackReadyCalls += 1; };
      globalThis.fetch = async (url) => {
        requestedArtifactUrl = url;
        return {
          ok: !failFetch,
          arrayBuffer: async () => delivery.bytes.buffer.slice(delivery.bytes.byteOffset, delivery.bytes.byteOffset + delivery.bytes.byteLength),
        };
      };
      globalThis.addEventListener = () => {};
      const moduleContext = vm.createContext({});
      const glimpseModule = new vm.SyntheticModule(["convertPptxToSvg"], function () {
        this.setExport("convertPptxToSvg", converter);
      }, { context: moduleContext });
      await glimpseModule.link(() => {});
      await glimpseModule.evaluate();
      const script = new vm.Script(viewerSource, {
        filename: viewerScript,
        importModuleDynamically: () => glimpseModule,
      });
      script.runInThisContext();
      await globalThis.PptxPresentationViewerReady;
      return viewerNodes;
    }

    const convertedCalls = [];
    const successfulViewerNodes = await runViewer(async (bytes, options) => {
      convertedBytes = Buffer.from(bytes);
      convertedOptions = options;
      convertedCalls.push({ bytes: Buffer.from(bytes), options });
      return {
        slides: [{
          slideNumber: options.slides[0],
          svg: '<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" data-slide="' + (options.slides[0] - 1) + '"></svg>',
        }],
      };
    });
    assert.equal(requestedArtifactUrl, globalThis.JarvysArtifact.url);
    assert.deepEqual(convertedBytes, delivery.bytes, "the viewer must pass the delivered PPTX bytes to PptxGlimpse");
    assert.deepEqual(convertedOptions, { textOutput: "text", width: 1280, slides: [1] });
    assert.equal(successfulViewerNodes.counter.textContent, "Diapositiva 1 de 4", "viewer error: " + successfulViewerNodes.status.textContent);
    assert.equal(viewerFallbackReadyCalls, 1, "the first rendered slide must clear the loading state");
    assert.equal(successfulViewerNodes.status.hidden, true);
    assert.equal(document.title, "Presentación");
    assert.equal(successfulViewerNodes.previous.disabled, true);
    assert.equal(successfulViewerNodes.next.disabled, false);
    assert.equal(successfulViewerNodes.stage.children.length, 1);
    assert.equal(successfulViewerNodes.stage.children[0].style.width, "296px");
    assert.equal(successfulViewerNodes.stage.children[0].style.height, "166.5px");
    successfulViewerNodes.next.click();
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(successfulViewerNodes.counter.textContent, "Diapositiva 2 de 4");
    assert.equal(successfulViewerNodes.previous.disabled, false);
    successfulViewerNodes.previous.click();
    await new Promise((resolve) => setImmediate(resolve));
    assert.equal(successfulViewerNodes.counter.textContent, "Diapositiva 1 de 4");
    assert.deepEqual(renderedSlideIndices, [0, 1, 0], "next and previous controls must render the selected slide");
    assert.deepEqual(convertedCalls.map((call) => call.options.slides[0]), [1, 2],
      "the viewer must convert the selected slide once and reuse its cached SVG when navigating back");

    failSlideImageDecode = false;
    const englishViewerNodes = await runViewer(async (_bytes, options) => ({
      slides: [{ slideNumber: options.slides[0], svg: '<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720"></svg>' }],
    }), "en-US", 1);
    assert.equal(document.title, "Presentation");
    assert.equal(englishViewerNodes.counter.textContent, "Slide 1 of 1");
    assert.equal(viewerFallbackReadyCalls, 1);
    assert.equal(englishViewerNodes.previous.textContent, "Previous");
    assert.equal(englishViewerNodes.next.textContent, "Next");

    failSlideImageDecode = true;
    const failedViewerNodes = await runViewer(async (_bytes, options) => ({
      slides: [{ slideNumber: options.slides[0], svg: '<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720"></svg>' }],
    }), "es-ES", 1);
    assert.equal(failedViewerNodes.status.hidden, false, "a failed slide render must show an on-screen alert");
    assert.match(failedViewerNodes.status.textContent, /No se pudo mostrar la diapositiva/);
    assert.equal(failedViewerNodes.stage.children.length, 0, "a failed render must not leave a silent empty slide");
    assert.equal(viewerFallbackReadyCalls, 1, "a visible slide failure must stop the standalone watchdog");

    const failedLoadViewerNodes = await runViewer(async () => { throw new Error("converter-should-not-run"); }, "es-ES", 1, true);
    assert.equal(failedLoadViewerNodes.counter.textContent, "", "a failed presentation load must clear the loading counter");
    assert.equal(failedLoadViewerNodes.status.hidden, false, "a failed presentation load must show its alert");
    assert.equal(viewerFallbackReadyCalls, 1, "a visible load failure must stop the standalone watchdog");
  }

  process.stdout.write("node-module-ok " + manifest.id + " " + delivery.bytes.length + " bytes\n");
}

main().catch((error) => {
  console.error(error && error.stack || error);
  process.exitCode = 1;
});
