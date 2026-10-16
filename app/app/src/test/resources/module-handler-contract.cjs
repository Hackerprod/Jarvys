"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const moduleDirectory = path.resolve(process.argv[2]);
const manifest = JSON.parse(fs.readFileSync(path.join(moduleDirectory, "manifest.json"), "utf8"));
const declaration = manifest.tools[0];
const handlerFile = path.join(moduleDirectory, declaration.handler.split("#")[0]);
const thumbnailPng = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/a1sAAAAASUVORK5CYII=";
const renderedTexts = [];

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
  const context = new Proxy({
    canvas,
    measureText(text) { return { width: String(text).length * 8 }; },
    fillText(text) { renderedTexts.push(String(text)); },
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
  createElement(tag) { return tag === "canvas" ? makeCanvas() : { setAttribute() {}, addEventListener() {} }; },
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
const host = {
  artifactDeliverBytes(base64, name, artifactType, title, thumbnailBase64, thumbnailMimeType) {
    const delivery = {
      bytes: Buffer.from(base64, "base64"),
      name,
      artifactType,
      title,
      thumbnail: Buffer.from(thumbnailBase64, "base64"),
      thumbnailMimeType,
    };
    deliveries.push(delivery);
    return JSON.stringify({ id: "node-contract-artifact" });
  },
};

async function main() {
  let argumentsObject;
  if (manifest.id === "com.jarvys.module.chart") {
    const chartBundle = require(path.join(moduleDirectory, "vendor/chart.umd.min.js"));
    assert.equal(typeof chartBundle, "function");
    globalThis.Chart = class {
      constructor(context, config) {
        assert.ok(context && context.canvas);
        assert.equal(config.type, "bar");
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
    globalThis.JSZip = require(path.join(moduleDirectory, "vendor/jszip.min.js"));
    const generatorFile = path.join(moduleDirectory, "vendor/pptxgen.min.js");
    vm.runInThisContext(fs.readFileSync(generatorFile, "utf8"), {
      filename: generatorFile,
      importModuleDynamically: (specifier) => import(specifier),
    });
    const rendererFile = path.join(moduleDirectory, "viewer/deck-renderer.js");
    vm.runInThisContext(fs.readFileSync(rendererFile, "utf8"), { filename: rendererFile });
    argumentsObject = {
      title: "Quarterly review",
      slides: [{ title: "Overview", body: "Sales improved" }],
    };
  } else {
    throw new Error("Unexpected bundled module id: " + manifest.id);
  }

  vm.runInThisContext(fs.readFileSync(handlerFile, "utf8"), { filename: handlerFile });
  const exportName = declaration.handler.split("#")[1];
  assert.equal(typeof globalThis[exportName], "function");
  const result = await globalThis[exportName](argumentsObject, host);
  assert.equal(result.success, true);
  assert.equal(result.artifactType, manifest.artifactTypes[0].type);
  assert.equal(deliveries.length, 1);

  const delivery = deliveries[0];
  assert.equal(delivery.artifactType, manifest.artifactTypes[0].type);
  assert.equal(delivery.thumbnailMimeType, "image/png");
  assert.deepEqual(Array.from(delivery.thumbnail.subarray(0, 4)), [0x89, 0x50, 0x4e, 0x47]);

  if (manifest.id === "com.jarvys.module.chart") {
    const artifact = JSON.parse(delivery.bytes.toString("utf8"));
    assert.equal(artifact.title, "Quarterly sales");
    assert.equal(artifact.chartType, "bar");
    assert.deepEqual(artifact.data.labels, ["Q1", "Q2"]);
  } else {
    assert.equal(delivery.name, "Quarterly review.pptx");
    assert.ok(renderedTexts.some((text) => text.includes("Overview")));
    const zip = await globalThis.JSZip.loadAsync(delivery.bytes);
    assert.ok(zip.file("[Content_Types].xml"));
    assert.ok(zip.file("ppt/slides/slide1.xml"));
    const slide = await zip.file("ppt/slides/slide1.xml").async("string");
    assert.match(slide, /Overview/);
  }

  process.stdout.write("node-module-ok " + manifest.id + " " + delivery.bytes.length + " bytes\n");
}

main().catch((error) => {
  console.error(error && error.stack || error);
  process.exitCode = 1;
});
