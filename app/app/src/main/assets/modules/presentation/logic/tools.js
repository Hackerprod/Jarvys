(function (global) {
  "use strict";

  function loadAssetScript(path) {
    return new Promise(function (resolve, reject) {
      var script = document.createElement("script");
      script.src = path;
      script.onload = resolve;
      script.onerror = function () { reject(new Error("A local presentation asset could not be loaded.")); };
      document.head.appendChild(script);
    });
  }

  async function loadRuntime() {
    if (!global.JSZip) await loadAssetScript("vendor/jszip.min.js");
    if (!global.PptxGenJS) await loadAssetScript("vendor/pptxgen.min.js");
    if (!global.PptxDeckRenderer) await loadAssetScript("viewer/deck-renderer.js");
  }

  function base64FromBytes(value) {
    var bytes = value instanceof Uint8Array ? value : new Uint8Array(value);
    var binary = "";
    for (var index = 0; index < bytes.length; index += 1) binary += String.fromCharCode(bytes[index]);
    return btoa(binary);
  }

  function bytesFromBase64(value) {
    var binary = atob(value);
    var bytes = new Uint8Array(binary.length);
    for (var index = 0; index < binary.length; index += 1) bytes[index] = binary.charCodeAt(index);
    return bytes;
  }

  function parseDeliveryResult(raw) {
    if (raw && typeof raw === "object") return raw;
    if (typeof raw === "string") return JSON.parse(raw);
    throw new Error("The host did not return an artifact reference.");
  }

  function hexColor(value) {
    return value.charAt(0) === "#" ? value.slice(1) : value;
  }

  function addSlideContent(pptx, source) {
    var slide = pptx.addSlide();
    if (source.backgroundColor) slide.background = { color: hexColor(source.backgroundColor) };
    if (source.title) slide.addText(source.title, {
      x: 0.7, y: 0.45, w: 12, h: 0.8, fontFace: "Aptos Display", fontSize: 30,
      bold: true, color: "24324A", margin: 0
    });
    if (source.body) slide.addText(source.body, {
      x: 0.8, y: source.title ? 1.55 : 0.75, w: 11.7, h: 2.5,
      fontFace: "Aptos", fontSize: 20, color: "344054", breakLine: false,
      valign: "top", margin: 0.08
    });
    (source.bullets || []).forEach(function (text, index) {
      slide.addText(text, {
        x: 1.05, y: (source.title ? 1.55 : 0.75) + index * 0.52, w: 11.4, h: 0.44,
        fontFace: "Aptos", fontSize: 18, color: "344054", bullet: { indent: 18 },
        hanging: 4, margin: 0.02
      });
    });
    (source.shapes || []).forEach(function (shape) {
      var options = { x: shape.x, y: shape.y, w: shape.w, h: shape.h };
      if (shape.fillColor) options.fill = { color: hexColor(shape.fillColor) };
      if (shape.lineColor || shape.lineWidth !== undefined) options.line = {
        color: hexColor(shape.lineColor || "344054"),
        width: shape.lineWidth === undefined ? 1 : shape.lineWidth
      };
      slide.addShape(shape.type, options);
      if (shape.text) slide.addText(shape.text, {
        x: shape.x, y: shape.y, w: shape.w, h: shape.h, fontFace: "Aptos",
        fontSize: shape.fontSize || 16, color: hexColor(shape.color || "24324A"),
        align: "center", valign: "mid", margin: 0.06
      });
    });
    (source.images || []).forEach(function (image) {
      slide.addImage({
        data: image.dataUrl,
        x: image.x,
        y: image.y,
        w: image.w,
        h: image.h,
        altText: image.altText || ""
      });
    });
  }

  async function createPresentation(argumentsObject, host) {
    if (!argumentsObject || !host || typeof host.artifactDeliverBytes !== "function") {
      throw new Error("The presentation host capability is unavailable.");
    }
    if (!Array.isArray(argumentsObject.slides) || argumentsObject.slides.length === 0) {
      throw new Error("Add at least one slide to the presentation.");
    }
    await loadRuntime();

    var pptx = new global.PptxGenJS();
    pptx.layout = "LAYOUT_WIDE";
    pptx.author = "Jarvys";
    pptx.subject = argumentsObject.title;
    pptx.title = argumentsObject.title;
    pptx.company = "Jarvys";
    argumentsObject.slides.forEach(function (slide) { addSlideContent(pptx, slide); });

    var pptxBase64 = await pptx.write({ outputType: "base64" });
    var pptxBytes = bytesFromBase64(pptxBase64);
    var thumbnailCanvas = document.createElement("canvas");
    await global.PptxDeckRenderer.renderFirstSlide(pptxBytes, thumbnailCanvas);
    var thumbnailBase64 = thumbnailCanvas.toDataURL("image/png").split(",")[1];
    var delivered = parseDeliveryResult(host.artifactDeliverBytes(
      pptxBase64,
      argumentsObject.title + ".pptx",
      "application/vnd.openxmlformats-officedocument.presentationml.presentation",
      argumentsObject.title,
      thumbnailBase64,
      "image/png"
    ));
    if (!delivered.id) throw new Error("The host did not confirm the presentation artifact id.");
    return {
      success: true,
      content: "PowerPoint artifact stored in this conversation. Artifact id: " + delivered.id,
      artifactId: delivered.id,
      artifactType: "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    };
  }

  global.createPresentation = createPresentation;
}(window));
