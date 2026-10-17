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
const drawnTextCalls = [];
const paintedFills = [];
const canvases = [];
const rendererInputs = [];

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
  canvases.push(canvas);
  const context = new Proxy({
    canvas,
    measureText(text) { return { width: String(text).length * 8 }; },
    fillText(text) {
      const value = String(text);
      renderedTexts.push(value);
      drawnTextCalls.push({ text: value, font: this.font, fillStyle: this.fillStyle });
    },
    fill() { paintedFills.push(this.fillStyle); },
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
    const rendererFile = path.join(moduleDirectory, "viewer/deck-renderer.js");
    vm.runInThisContext(fs.readFileSync(rendererFile, "utf8"), { filename: rendererFile });
    const originalRenderer = globalThis.PptxDeckRenderer;
    globalThis.PptxDeckRenderer = Object.assign({}, originalRenderer, {
      loadDeck(bytes) {
        rendererInputs.push(Buffer.from(bytes));
        return originalRenderer.loadDeck(bytes);
      },
      renderFirstSlide(bytes, canvas) {
        rendererInputs.push(Buffer.from(bytes));
        return originalRenderer.renderFirstSlide(bytes, canvas);
      },
    });
    const generatorFile = path.join(moduleDirectory, "vendor/pptxgen.min.js");
    vm.runInThisContext(fs.readFileSync(generatorFile, "utf8"), {
      filename: generatorFile,
      importModuleDynamically: (specifier) => import(specifier),
    });
    argumentsObject = {
      title: "Quarterly review",
      slides: [
        { title: "Overview", body: "Four-slide renderer fixture" },
        {
          title: "Two-bar comparison",
          backgroundColor: "F4F6FB",
          shapes: [
            { type: "rect", x: 2, y: 2, w: 2, h: 3, fillColor: "536DFE", lineColor: "536DFE", text: "North 42", fontSize: 22, color: "FFFFFF" },
            { type: "rect", x: 5, y: 1.4, w: 2, h: 3.6, fillColor: "18A999", lineColor: "18A999", text: "South 57", fontSize: 22, color: "FFFFFF" },
            { type: "rect", x: 8, y: 2, w: 3.5, h: 0.7, fillColor: "F4F6FB", lineColor: "F4F6FB", text: "North region", fontSize: 18, color: "344054" },
            { type: "rect", x: 8, y: 3, w: 3.5, h: 0.7, fillColor: "F4F6FB", lineColor: "F4F6FB", text: "South region", fontSize: 18, color: "344054" },
          ],
        },
        {
          title: "Next steps",
          body: "The four slides are part of the delivered PPTX.",
          backgroundColor: "14233B",
          images: [{ dataUrl: "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/a1sAAAAASUVORK5CYII=", x: 10, y: 3.4, w: 1, h: 1, altText: "Sample image" }],
        },
        {
          title: "Sales by region",
          backgroundColor: "F4F6FB",
          chart: {
            title: "Revenue",
            labels: ["North", "South"],
            datasets: [{ name: "Sales", values: [42, 57], color: "2459D3" }]
          },
          table: {
            headers: ["Region", "Revenue"],
            rows: [["North", "42"], ["South", "57"]]
          },
        },
      ],
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
    const zip = await globalThis.JSZip.loadAsync(delivery.bytes);
    assert.ok(zip.file("[Content_Types].xml"));
    assert.ok(zip.file("ppt/slides/slide1.xml"));
    const slide = await zip.file("ppt/slides/slide1.xml").async("string");
    assert.match(slide, /Overview/);
    assert.match(slide, /Four-slide renderer fixture/);
    assert.ok(Object.keys(zip.files).some((name) => name.startsWith("ppt/media/") && name.endsWith(".png")));
    assert.ok(zip.file("ppt/slides/slide2.xml"));
    const comparisonXml = await zip.file("ppt/slides/slide2.xml").async("string");
    ["Two-bar comparison", "North 42", "South 57", "North region", "South region"].forEach((text) => {
      assert.ok(comparisonXml.includes(text), "PPTX slide 2 must contain " + text);
    });
    ["F4F6FB", "536DFE", "18A999", "FFFFFF", "344054", "101828"].forEach((color) => {
      assert.ok(comparisonXml.includes(color), "PPTX slide 2 must contain color " + color);
    });
    const deck = await globalThis.PptxDeckRenderer.loadDeck(delivery.bytes);
    assert.equal(deck.slidePaths.length, 4);
    await globalThis.PptxDeckRenderer.renderSlide(deck, 1, makeCanvas());
    ["Two-bar comparison", "North 42", "South 57", "North region", "South region"].forEach((text) => {
      assert.ok(renderedTexts.some((rendered) => rendered.includes(text)), "renderer must draw " + text);
    });
    assert.ok(drawnTextCalls.some((call) => call.text.includes("Two-bar comparison") && call.font.includes("48px")));
    assert.ok(drawnTextCalls.some((call) => call.text.includes("North 42") && call.fillStyle === "#FFFFFF"));
    assert.ok(drawnTextCalls.some((call) => call.text.includes("South 57") && call.fillStyle === "#FFFFFF"));
    ["#F4F6FB", "#536DFE", "#18A999"].forEach((color) => assert.ok(paintedFills.includes(color)));

    const unsupportedZip = await globalThis.JSZip.loadAsync(delivery.bytes);
    unsupportedZip.file("ppt/slides/slide2.xml", comparisonXml.replace(
      "</p:spTree>", "<p:graphicFrame/></p:spTree>",
    ));
    const unsupportedDeck = await globalThis.PptxDeckRenderer.loadDeck(
      await unsupportedZip.generateAsync({ type: "uint8array" }),
    );
    const unsupportedCanvas = makeCanvas();
    await globalThis.PptxDeckRenderer.renderSlide(unsupportedDeck, 1, unsupportedCanvas);
    assert.match(unsupportedCanvas.presentationWarnings.join(" "), /gráfico.*tabla/);

    // Stage dimensions are measured after the warning and nav auto-rows have taken their space.
    const stageHeight = 210;
    const stagePadding = 12;
    const fit = globalThis.PptxDeckRenderer.fitSlideToBounds(296, stageHeight - stagePadding * 2, deck.width, deck.height);
    assert.equal(fit.width, 296);
    assert.equal(fit.height, 166.5);
    const slideBottom = stagePadding + (stageHeight - stagePadding * 2 - fit.height) / 2 + fit.height;
    assert.ok(fit.width <= 296 && slideBottom <= stageHeight - stagePadding);

    const darkSlide = await zip.file("ppt/slides/slide3.xml").async("string");
    assert.match(darkSlide, /14233B/);
    assert.match(darkSlide, /Next steps/);
    assert.match(darkSlide, /FFFFFF/, "default text on a dark slide must use a contrasting color");
    const visualSlide = await zip.file("ppt/slides/slide4.xml").async("string");
    ["Sales by region", "Region", "Revenue", "North", "South", "42", "57"].forEach((text) => {
      assert.ok(visualSlide.includes(text), "PPTX slide 4 must contain " + text);
    });
    const chartFile = zip.file("ppt/charts/chart1.xml");
    assert.ok(chartFile, "the chart must be native PowerPoint chart data");
    const chartXml = await chartFile.async("string");
    assert.ok(chartXml.includes("2459D3"));
    assert.match(chartXml, /<c:showVal val="1"\/>/, "chart data values must be enabled");
    assert.match(chartXml, /<c:v>North<\/c:v>/);
    assert.match(chartXml, /<c:v>42<\/c:v>/);
    const tableXml = visualSlide;
    assert.equal((tableXml.match(/<a:tbl>/g) || []).length, 1, "the table must be a native PowerPoint table");
    assert.ok(tableXml.includes("101828"), "table text must contrast with its pale cell fills");
    assert.ok(rendererInputs.some((input) => input.equals(delivery.bytes)), "the generated PPTX bytes must reach the thumbnail renderer");

    const viewerHtml = fs.readFileSync(path.join(moduleDirectory, "viewer/presentation.html"), "utf8");
    assert.match(viewerHtml, /grid-template-rows:\s*auto\s+minmax\(0,\s*1fr\)\s+auto\s+auto/);
    assert.ok(viewerHtml.indexOf("class=\"stage\"") < viewerHtml.indexOf("<nav"));
    assert.doesNotMatch(viewerHtml.match(/nav\s*\{[^}]*\}/)?.[0] || "", /position\s*:\s*absolute/);
    assert.match(viewerHtml, /<script src="presentation\.js"><\/script>/);
    assert.doesNotMatch(viewerHtml, /deck-renderer\.js/, "the user-visible viewer must have one renderer");

    const viewerScript = path.join(moduleDirectory, "viewer/presentation.js");
    const viewerSource = fs.readFileSync(viewerScript, "utf8");
    let failSlideImageDecode = false;
    let requestedArtifactUrl = "";
    let convertedBytes = null;
    let convertedOptions = null;
    let renderedSlideIndices = [];

    function setupViewerNodes() {
      const viewerNodes = {
        stage: {
          clientWidth: 320,
          clientHeight: 210,
          children: [],
          replaceChildren(...children) { this.children = children; },
        },
        "screen-title": { textContent: "" },
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

    async function runViewer(converter, language = "es-ES") {
      const viewerNodes = setupViewerNodes();
      Object.defineProperty(globalThis, "navigator", {
        value: { userAgent: "node-module-contract", language }, configurable: true,
      });
      globalThis.JarvysArtifact = { url: "https://modules.jarvys.invalid/module-artifacts/fixture" };
      globalThis.fetch = async (url) => {
        requestedArtifactUrl = url;
        return {
          ok: true,
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

    const successfulViewerNodes = await runViewer(async (bytes, options) => {
      convertedBytes = Buffer.from(bytes);
      convertedOptions = options;
      return {
        slides: [0, 1, 2, 3].map((index) => ({
          svg: '<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720" data-slide="' + index + '"></svg>',
        })),
      };
    });
    assert.equal(requestedArtifactUrl, globalThis.JarvysArtifact.url);
    assert.deepEqual(convertedBytes, delivery.bytes, "the viewer must pass the delivered PPTX bytes to PptxGlimpse");
    assert.deepEqual(convertedOptions, { textOutput: "text", width: 1280 });
    assert.equal(successfulViewerNodes.counter.textContent, "Diapositiva 1 de 4", "viewer error: " + successfulViewerNodes.status.textContent);
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

    failSlideImageDecode = false;
    const englishViewerNodes = await runViewer(async () => ({
      slides: [{ svg: '<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720"></svg>' }],
    }), "en-US");
    assert.equal(document.title, "Presentation");
    assert.equal(englishViewerNodes.counter.textContent, "Slide 1 of 1");
    assert.equal(englishViewerNodes.previous.textContent, "Previous");
    assert.equal(englishViewerNodes.next.textContent, "Next");

    failSlideImageDecode = true;
    const failedViewerNodes = await runViewer(async () => ({
      slides: [{ svg: '<svg xmlns="http://www.w3.org/2000/svg" width="1280" height="720"></svg>' }],
    }));
    assert.equal(failedViewerNodes.status.hidden, false, "a failed slide render must show an on-screen alert");
    assert.match(failedViewerNodes.status.textContent, /No se pudo mostrar la diapositiva/);
    assert.equal(failedViewerNodes.stage.children.length, 0, "a failed render must not leave a silent empty slide");
  }

  process.stdout.write("node-module-ok " + manifest.id + " " + delivery.bytes.length + " bytes\n");
}

main().catch((error) => {
  console.error(error && error.stack || error);
  process.exitCode = 1;
});
