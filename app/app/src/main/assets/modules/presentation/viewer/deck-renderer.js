(function (global) {
  "use strict";

  var PRESENTATION_NS = "http://schemas.openxmlformats.org/presentationml/2006/main";
  var DRAWING_NS = "http://schemas.openxmlformats.org/drawingml/2006/main";
  var OFFICE_REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
  var PACKAGE_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships";
  var EMU_PER_INCH = 914400;
  var EMU_PER_POINT = EMU_PER_INCH / 72;
  var CANVAS_WIDTH = 1280;

  function parseXml(source) {
    var documentValue = new DOMParser().parseFromString(source, "application/xml");
    if (documentValue.getElementsByTagName("parsererror").length) {
      throw new Error("A presentation XML part could not be read.");
    }
    return documentValue;
  }

  function descendants(node, namespace, name) {
    if (!node) return [];
    return Array.prototype.slice.call(node.getElementsByTagNameNS(namespace, name));
  }

  function firstDescendant(node, namespace, name) {
    if (!node) return null;
    return node.getElementsByTagNameNS(namespace, name)[0] || null;
  }

  function positiveAttribute(node, name, fallback) {
    if (!node) return fallback;
    var value = Number(node.getAttribute(name));
    return Number.isFinite(value) && value > 0 ? value : fallback;
  }

  function resolvePackagePath(sourceFile, target) {
    if (!target || target.indexOf(":") >= 0 || target.indexOf("\\") >= 0) return null;
    var parts = target.charAt(0) === "/" ? [] : sourceFile.split("/").slice(0, -1);
    target.replace(/^\//, "").split("/").forEach(function (part) {
      if (!part || part === ".") return;
      if (part === "..") parts.pop();
      else parts.push(part);
    });
    return parts.join("/");
  }

  async function xmlPart(zip, path) {
    var part = zip.file(path);
    if (!part) throw new Error("A required presentation part is missing.");
    return parseXml(await part.async("string"));
  }

  async function relationships(zip, sourcePath) {
    var directory = sourcePath.slice(0, sourcePath.lastIndexOf("/") + 1);
    var name = sourcePath.slice(sourcePath.lastIndexOf("/") + 1);
    var relPath = directory + "_rels/" + name + ".rels";
    var part = zip.file(relPath);
    if (!part) return new Map();
    var documentValue = parseXml(await part.async("string"));
    var result = new Map();
    descendants(documentValue, PACKAGE_REL_NS, "Relationship").forEach(function (relationship) {
      if (relationship.getAttribute("TargetMode") === "External") return;
      result.set(relationship.getAttribute("Id"), resolvePackagePath(sourcePath, relationship.getAttribute("Target")));
    });
    return result;
  }

  function presentationSlidePaths(presentation, rels) {
    var ordered = descendants(presentation, PRESENTATION_NS, "sldId").map(function (slideId) {
      return rels.get(slideId.getAttributeNS(OFFICE_REL_NS, "id"));
    }).filter(Boolean);
    if (ordered.length) return ordered;
    throw new Error("The presentation does not contain readable slides.");
  }

  async function loadDeck(bytes) {
    if (!global.JSZip) throw new Error("The local ZIP reader is unavailable.");
    var zip = await global.JSZip.loadAsync(bytes);
    var presentationPath = "ppt/presentation.xml";
    var presentation = await xmlPart(zip, presentationPath);
    var rels = await relationships(zip, presentationPath);
    var size = firstDescendant(presentation, PRESENTATION_NS, "sldSz");
    var width = positiveAttribute(size, "cx", 16 * EMU_PER_INCH);
    var height = positiveAttribute(size, "cy", 9 * EMU_PER_INCH);
    return {
      zip: zip,
      width: width,
      height: height,
      slidePaths: presentationSlidePaths(presentation, rels)
    };
  }

  function colorFrom(node, fallback) {
    if (!node) return fallback;
    var rgb = firstDescendant(node, DRAWING_NS, "srgbClr");
    if (rgb) return "#" + rgb.getAttribute("val");
    var system = firstDescendant(node, DRAWING_NS, "sysClr");
    if (system && system.getAttribute("lastClr")) return "#" + system.getAttribute("lastClr");
    var scheme = firstDescendant(node, DRAWING_NS, "schemeClr");
    if (scheme) {
      var mapped = {
        tx1: "#24324a", dk1: "#000000", lt1: "#ffffff", tx2: "#344054",
        dk2: "#344054", lt2: "#f4f6fb", accent1: "#536dfe", accent2: "#18a999",
        accent3: "#f79009", accent4: "#7f56d9", accent5: "#f04438", accent6: "#06aed4",
        hlink: "#175cd3", folHlink: "#6941c6"
      };
      return mapped[scheme.getAttribute("val")] || fallback;
    }
    return fallback;
  }

  function shapeFill(shapeProperties) {
    if (!shapeProperties || firstDescendant(shapeProperties, DRAWING_NS, "noFill")) return null;
    return colorFrom(firstDescendant(shapeProperties, DRAWING_NS, "solidFill"), null);
  }

  function shapeLine(shapeProperties) {
    var line = firstDescendant(shapeProperties, DRAWING_NS, "ln");
    if (!line || firstDescendant(line, DRAWING_NS, "noFill")) return null;
    var width = Number(line.getAttribute("w"));
    return {
      color: colorFrom(firstDescendant(line, DRAWING_NS, "solidFill"), "#667085"),
      width: Number.isFinite(width) && width > 0 ? width : EMU_PER_POINT
    };
  }

  function paragraphText(paragraph) {
    return descendants(paragraph, DRAWING_NS, "t").map(function (text) { return text.textContent || ""; }).join("");
  }

  function paragraphStyle(paragraph, shapeTextBody) {
    var paragraphProperties = firstDescendant(paragraph, DRAWING_NS, "pPr");
    var runProperties = firstDescendant(paragraph, DRAWING_NS, "rPr") ||
      firstDescendant(paragraph, DRAWING_NS, "defRPr") ||
      firstDescendant(paragraph, DRAWING_NS, "endParaRPr");
    var fontSize = runProperties ? Number(runProperties.getAttribute("sz")) : NaN;
    var bold = runProperties && (runProperties.getAttribute("b") === "1" || runProperties.getAttribute("b") === "true");
    var italic = runProperties && (runProperties.getAttribute("i") === "1" || runProperties.getAttribute("i") === "true");
    var alignment = paragraphProperties && paragraphProperties.getAttribute("algn");
    var color = colorFrom(firstDescendant(runProperties || paragraph, DRAWING_NS, "solidFill"), "#24324a");
    var bullet = paragraphProperties && firstDescendant(paragraphProperties, DRAWING_NS, "buChar");
    var bodyProperties = firstDescendant(shapeTextBody, DRAWING_NS, "bodyPr");
    var vertical = bodyProperties && bodyProperties.getAttribute("anchor");
    return {
      fontSizeEmu: Number.isFinite(fontSize) && fontSize > 0 ? fontSize / 100 * EMU_PER_POINT : 18 * EMU_PER_POINT,
      bold: Boolean(bold),
      italic: Boolean(italic),
      alignment: alignment === "ctr" ? "center" : alignment === "r" ? "right" : "left",
      color: color,
      bullet: Boolean(bullet),
      vertical: vertical
    };
  }

  function bodyInsets(textBody) {
    var properties = firstDescendant(textBody, DRAWING_NS, "bodyPr");
    return {
      left: Number(properties && properties.getAttribute("lIns")) || EMU_PER_INCH / 10,
      right: Number(properties && properties.getAttribute("rIns")) || EMU_PER_INCH / 10,
      top: Number(properties && properties.getAttribute("tIns")) || EMU_PER_INCH / 20,
      bottom: Number(properties && properties.getAttribute("bIns")) || EMU_PER_INCH / 20
    };
  }

  function wrapText(context, text, width) {
    var words = text.split(" ");
    var lines = [];
    var line = "";
    words.forEach(function (word) {
      var candidate = line ? line + " " + word : word;
      if (line && context.measureText(candidate).width > width) {
        lines.push(line);
        line = word;
      } else line = candidate;
    });
    if (line) lines.push(line);
    return lines;
  }

  function drawText(context, shape, bounds) {
    var textBody = firstDescendant(shape, PRESENTATION_NS, "txBody");
    if (!textBody || bounds.w <= 0 || bounds.h <= 0) return;
    var paragraphs = descendants(textBody, DRAWING_NS, "p");
    var insets = bodyInsets(textBody);
    var top = bounds.y + insets.top;
    var availableWidth = Math.max(0, bounds.w - insets.left - insets.right);
    var availableHeight = Math.max(0, bounds.h - insets.top - insets.bottom);
    var consumed = 0;
    paragraphs.forEach(function (paragraph) {
      var text = paragraphText(paragraph);
      if (!text) return;
      var style = paragraphStyle(paragraph, textBody);
      context.save();
      context.fillStyle = style.color;
      context.font = (style.italic ? "italic " : "") + (style.bold ? "bold " : "") + style.fontSizeEmu + "px Aptos, Arial, sans-serif";
      context.textBaseline = "top";
      context.textAlign = style.alignment;
      var lines = wrapText(context, (style.bullet ? "• " : "") + text, availableWidth);
      var lineHeight = style.fontSizeEmu * 1.25;
      lines.forEach(function (line, lineIndex) {
        var y = top + consumed + lineIndex * lineHeight;
        if (y > top + availableHeight) return;
        var x = bounds.x + insets.left;
        if (style.alignment === "center") x += availableWidth / 2;
        if (style.alignment === "right") x += availableWidth;
        context.fillText(line, x, y, availableWidth);
      });
      consumed += lines.length * lineHeight;
      context.restore();
    });
  }

  function transformFor(shapeProperties, bounds, context) {
    var transform = firstDescendant(shapeProperties, DRAWING_NS, "xfrm");
    var rotation = Number(transform && transform.getAttribute("rot"));
    if (!Number.isFinite(rotation) || rotation === 0) return;
    context.translate(bounds.x + bounds.w / 2, bounds.y + bounds.h / 2);
    context.rotate(rotation / 60000 * Math.PI / 180);
    context.translate(-(bounds.x + bounds.w / 2), -(bounds.y + bounds.h / 2));
  }

  function shapeBounds(shapeProperties) {
    var transform = firstDescendant(shapeProperties, DRAWING_NS, "xfrm");
    var offset = firstDescendant(transform, DRAWING_NS, "off");
    var extent = firstDescendant(transform, DRAWING_NS, "ext");
    if (!offset || !extent) return null;
    return {
      x: Number(offset.getAttribute("x")),
      y: Number(offset.getAttribute("y")),
      w: Number(extent.getAttribute("cx")),
      h: Number(extent.getAttribute("cy"))
    };
  }

  function drawShape(context, shape) {
    var shapeProperties = firstDescendant(shape, PRESENTATION_NS, "spPr");
    var bounds = shapeBounds(shapeProperties);
    if (!bounds || ![bounds.x, bounds.y, bounds.w, bounds.h].every(Number.isFinite)) return;
    context.save();
    transformFor(shapeProperties, bounds, context);
    var geometry = firstDescendant(shapeProperties, DRAWING_NS, "prstGeom");
    var kind = geometry ? geometry.getAttribute("prst") : "rect";
    var fill = shapeFill(shapeProperties);
    var line = shapeLine(shapeProperties);
    context.beginPath();
    if (kind === "ellipse" || kind === "arc" || kind === "pie") {
      context.ellipse(bounds.x + bounds.w / 2, bounds.y + bounds.h / 2,
        Math.abs(bounds.w / 2), Math.abs(bounds.h / 2), 0, 0, Math.PI * 2);
    } else if (kind === "roundRect" && typeof context.roundRect === "function") {
      context.roundRect(bounds.x, bounds.y, bounds.w, bounds.h, Math.min(bounds.w, bounds.h) / 12);
    } else if (kind === "line" || kind === "straightConnector1") {
      context.moveTo(bounds.x, bounds.y);
      context.lineTo(bounds.x + bounds.w, bounds.y + bounds.h);
    } else context.rect(bounds.x, bounds.y, bounds.w, bounds.h);
    if (fill && kind !== "line" && kind !== "straightConnector1") {
      context.fillStyle = fill;
      context.fill();
    }
    if (line) {
      context.strokeStyle = line.color;
      context.lineWidth = line.width;
      context.stroke();
    }
    context.restore();
    drawText(context, shape, bounds);
  }

  function mimeFor(path) {
    var extension = path.slice(path.lastIndexOf(".") + 1).toLowerCase();
    return extension === "jpg" || extension === "jpeg" ? "image/jpeg" :
      extension === "gif" ? "image/gif" : extension === "svg" ? "image/svg+xml" : "image/png";
  }

  async function drawPicture(context, shape, zip, rels) {
    var shapeProperties = firstDescendant(shape, PRESENTATION_NS, "spPr");
    var bounds = shapeBounds(shapeProperties);
    var blip = firstDescendant(shape, DRAWING_NS, "blip");
    var target = blip && rels.get(blip.getAttributeNS(OFFICE_REL_NS, "embed"));
    var part = target && zip.file(target);
    if (!bounds || !part) return;
    var base64 = await part.async("base64");
    var source = "data:" + mimeFor(target) + ";base64," + base64;
    var image = new Image();
    await new Promise(function (resolve, reject) {
      image.onload = resolve;
      image.onerror = function () { reject(new Error("A slide image could not be rendered.")); };
      image.src = source;
    });
    context.save();
    transformFor(shapeProperties, bounds, context);
    context.drawImage(image, bounds.x, bounds.y, bounds.w, bounds.h);
    context.restore();
  }

  async function renderSlide(deck, index, canvas) {
    var slidePath = deck.slidePaths[index];
    if (!slidePath) throw new Error("The requested slide is unavailable.");
    var slideDocument = await xmlPart(deck.zip, slidePath);
    var rels = await relationships(deck.zip, slidePath);
    var width = CANVAS_WIDTH;
    var height = Math.round(width * deck.height / deck.width);
    canvas.width = width;
    canvas.height = height;
    canvas.setAttribute("aria-label", "Diapositiva " + (index + 1));
    var context = canvas.getContext("2d");
    var scale = width / deck.width;
    context.setTransform(scale, 0, 0, scale, 0, 0);
    context.fillStyle = "#ffffff";
    context.fillRect(0, 0, deck.width, deck.height);

    var background = firstDescendant(slideDocument, PRESENTATION_NS, "bg");
    var backgroundColor = colorFrom(firstDescendant(background, DRAWING_NS, "solidFill"), null);
    if (backgroundColor) {
      context.fillStyle = backgroundColor;
      context.fillRect(0, 0, deck.width, deck.height);
    }

    var shapes = descendants(slideDocument, PRESENTATION_NS, "sp");
    var pictures = descendants(slideDocument, PRESENTATION_NS, "pic");
    var content = shapes.concat(pictures).sort(function (left, right) {
      return left.compareDocumentPosition(right) & 2 ? 1 : -1;
    });
    for (var shapeIndex = 0; shapeIndex < content.length; shapeIndex += 1) {
      if (content[shapeIndex].localName === "pic") {
        await drawPicture(context, content[shapeIndex], deck.zip, rels);
      } else drawShape(context, content[shapeIndex]);
    }
    return canvas;
  }

  async function renderFirstSlide(bytes, canvas) {
    var deck = await loadDeck(bytes);
    return renderSlide(deck, 0, canvas);
  }

  global.PptxDeckRenderer = {
    loadDeck: loadDeck,
    renderSlide: renderSlide,
    renderFirstSlide: renderFirstSlide
  };
}(window));
