(function (global) {
  "use strict";

  // Match the host's 640x360 decode target and its existing 2x card-density margin.
  var MAX_THUMBNAIL_BASE64_CHARS = Math.ceil(1280 * 720 * 4 / 3) * 4;
  var SLIDE_WIDTH = 13.333333;
  var SLIDE_HEIGHT = 7.5;
  var VISUAL_BOTTOM_MARGIN = 0.55;
  var VISUAL_GAP = 0.3;
  var TEXT_LINE_SPACING = 1;
  var COLORS = {
    background: "F7F9FC",
    ink: "101828",
    body: "344054",
    accent: ["2459D3", "138A8A", "C46B12", "7653B8", "C04452", "1675A9"],
    accentDark: ["79A7FF", "48C4B6", "FFBF69", "B69CFF", "FF8C9A", "73C8F5"],
    grid: "D0D5DD",
    stripe: "F2F4F7",
    border: "D0D5DD"
  };

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

  function relativeLuminance(color) {
    var value = hexColor(color);
    function channel(offset) {
      var srgb = parseInt(value.slice(offset, offset + 2), 16) / 255;
      return srgb <= 0.04045 ? srgb / 12.92 : Math.pow((srgb + 0.055) / 1.055, 2.4);
    }
    return 0.2126 * channel(0) + 0.7152 * channel(2) + 0.0722 * channel(4);
  }

  function contrastingTextColor(background) {
    var luminance = relativeLuminance(background);
    var darkContrast = (luminance + 0.05) / (relativeLuminance(COLORS.ink) + 0.05);
    var lightContrast = 1.05 / (luminance + 0.05);
    return darkContrast >= lightContrast ? COLORS.ink : "FFFFFF";
  }

  function validateVisualInput(source, slideNumber, kind) {
    ["x", "y", "w", "h"].forEach(function (key) {
      if (source[key] !== undefined && !Number.isFinite(source[key])) {
        throw new Error("Slide " + slideNumber + ": " + kind + " " + key + " must be a finite number.");
      }
    });
    if (source.w !== undefined && source.w <= 0) {
      throw new Error("Slide " + slideNumber + ": " + kind + " width must be greater than zero.");
    }
    if (source.h !== undefined && source.h <= 0) {
      throw new Error("Slide " + slideNumber + ": " + kind + " height must be greater than zero.");
    }
  }

  function placeVisual(source, y, height, slideNumber, kind) {
    validateVisualInput(source, slideNumber, kind);
    return {
      x: source.x === undefined ? 0.8 : source.x,
      y: source.y === undefined ? y : source.y,
      w: source.w === undefined ? 11.75 : source.w,
      h: source.h === undefined ? height : source.h
    };
  }

  function hasChartData(chart) {
    return Boolean(chart && Array.isArray(chart.labels) && chart.labels.length > 0 &&
      Array.isArray(chart.datasets) && chart.datasets.some(function (dataset) {
        return dataset && Array.isArray(dataset.values) && dataset.values.length > 0;
      }));
  }

  function chartValuesMatchLabels(chart) {
    return chart.datasets.every(function (dataset) {
      return dataset && Array.isArray(dataset.values) && dataset.values.length === chart.labels.length;
    });
  }

  function addChart(pptx, slide, chart, backgroundColor, textColor, bounds) {
    var datasets = chart.datasets.filter(function (dataset) {
      return dataset && Array.isArray(dataset.values) && dataset.values.length > 0;
    });
    var data = datasets.map(function (dataset) {
      return { name: dataset.name, labels: chart.labels, values: dataset.values };
    });
    var palette = relativeLuminance(backgroundColor) < 0.15 ? COLORS.accentDark : COLORS.accent;
    var chartColors = datasets.map(function (dataset, index) {
      return hexColor(dataset.color || palette[index % palette.length]);
    });
    slide.addChart(pptx.ChartType.bar, data, {
      x: bounds.x, y: bounds.y, w: bounds.w, h: bounds.h,
      catAxisLabelColor: textColor,
      catAxisLabelFontFace: "Arial",
      catAxisLabelFontSize: 15,
      valAxisLabelColor: textColor,
      valAxisLabelFontFace: "Arial",
      valAxisLabelFontSize: 15,
      valGridLine: { color: relativeLuminance(backgroundColor) > 0.15 ? COLORS.grid : "475467", width: 1 },
      catGridLine: { style: "none" },
      chartColors: chartColors,
      showLegend: data.length > 1,
      legendColor: textColor,
      legendFontFace: "Arial",
      legendFontSize: 15,
      showTitle: Boolean(chart.title),
      title: chart.title || "",
      titleColor: textColor,
      titleFontFace: "Cambria",
      titleFontSize: 18,
      showValue: true,
      dataLabelPosition: "outEnd",
      dataLabelColor: textColor,
      dataLabelFontFace: "Arial",
      dataLabelFontSize: 15,
      dataLabelFormatCode: "General",
      showSerName: false,
      showBorder: false
    });
  }

  function addTable(slide, table, textColor, bounds) {
    var rows = [table.headers.map(function (value) {
      return { text: value, options: { bold: true, color: "FFFFFF", fill: "193B61" } };
    })];
    table.rows.forEach(function (row, rowIndex) {
      var rowFill = rowIndex % 2 === 0 ? "FFFFFF" : COLORS.stripe;
      rows.push(row.map(function (value) {
        return {
          text: value,
          options: { color: contrastingTextColor(rowFill), fill: rowFill }
        };
      }));
    });
    slide.addTable(rows, {
      x: bounds.x, y: bounds.y, w: bounds.w, h: bounds.h,
      fontFace: "Arial",
      fontSize: 15,
      color: textColor,
      border: { type: "solid", color: COLORS.border, pt: 0.8 },
      margin: 7,
      valign: "middle",
      autoFit: false
    });
  }

  function measureTextLineCount(text, width, fontSize, fontFace, leftIndent) {
    var canvas = document.createElement("canvas");
    var context = canvas.getContext("2d");
    context.font = (fontSize * 96 / 72) + "px " + fontFace;
    var availableWidth = (width - leftIndent) * 96;
    var lineCount = 0;
    String(text).split(/\r?\n/).forEach(function (paragraph) {
      var words = paragraph.split(/\s+/).filter(Boolean);
      if (words.length === 0) {
        lineCount += 1;
        return;
      }
      var line = "";
      words.forEach(function (word) {
        var candidate = line ? line + " " + word : word;
        if (line && context.measureText(candidate).width > availableWidth) {
          lineCount += 1;
          line = word;
        } else {
          line = candidate;
        }
      });
      lineCount += 1;
    });
    return lineCount;
  }

  function measuredTextHeight(text, width, fontSize, fontFace, margin, leftIndent) {
    var lines = measureTextLineCount(text, width - margin * 2, fontSize, fontFace, leftIndent || 0);
    return lines * fontSize / 72 * TEXT_LINE_SPACING + margin * 2;
  }

  function validateBounds(bounds, slideNumber, kind) {
    if (![bounds.x, bounds.y, bounds.w, bounds.h].every(Number.isFinite)) {
      throw new Error("Slide " + slideNumber + ": " + kind + " position and size must be finite numbers.");
    }
    if (bounds.w <= 0 || bounds.h <= 0) {
      throw new Error("Slide " + slideNumber + ": " + kind + " width and height must be greater than zero.");
    }
    if (bounds.y + bounds.h > SLIDE_HEIGHT && (kind === "body text" || kind.indexOf("bullet ") === 0)) {
      throw new Error("Slide " + slideNumber + ": text content does not fit on the slide; shorten the body or bullets.");
    }
    if (bounds.x < 0 || bounds.y < 0 || bounds.x + bounds.w > SLIDE_WIDTH || bounds.y + bounds.h > SLIDE_HEIGHT) {
      throw new Error("Slide " + slideNumber + ": " + kind + " exceeds slide bounds; move it within the slide or reduce its size.");
    }
  }

  function addSlideContent(pptx, source, slideNumber) {
    if (source.chart && !hasChartData(source.chart)) {
      throw new Error("Slide " + slideNumber + ": chart has no data.");
    }
    if (source.chart && !chartValuesMatchLabels(source.chart)) {
      throw new Error("Slide " + slideNumber + ": chart labels and values must have the same length.");
    }
    var slide = pptx.addSlide();
    var backgroundColor = hexColor(source.backgroundColor || COLORS.background);
    var textColor = contrastingTextColor(backgroundColor);
    slide.background = { color: backgroundColor };

    var contentY = source.title ? 1.55 : 0.72;
    var renderChart = hasChartData(source.chart);
    var visualCount = Number(renderChart) + Number(Boolean(source.table));
    var textPlacements = [];
    if (source.title) {
      slide.addText(source.title, {
        x: 0.65, y: 0.55, w: 12.0, h: 0.78,
        fontFace: "Cambria", fontSize: 36, bold: true,
        color: textColor, margin: 0, breakLine: false
      });
    }
    if (source.body) {
      var bodyHeight = measuredTextHeight(source.body, 11.7, 20, "Arial", 0.06, 0);
      var bodyBounds = { x: 0.8, y: contentY, w: 11.7, h: bodyHeight };
      validateBounds(bodyBounds, slideNumber, "body text");
      textPlacements.push({ text: source.body, bounds: bodyBounds, options: {
        fontFace: "Arial", fontSize: 20, color: textColor, valign: "top", margin: 0.06,
        lineSpacingMultiple: TEXT_LINE_SPACING
      } });
      contentY += bodyHeight + 0.12;
    }
    (source.bullets || []).forEach(function (text, bulletIndex) {
      var bulletHeight = measuredTextHeight(text, 11.5, 18, "Arial", 0.02, 18 / 72);
      var bulletBounds = { x: 1.0, y: contentY, w: 11.5, h: bulletHeight };
      validateBounds(bulletBounds, slideNumber, "bullet " + (bulletIndex + 1));
      textPlacements.push({ text: text, bounds: bulletBounds, options: {
        fontFace: "Arial", fontSize: 18, color: textColor,
        bullet: { indent: 18 }, hanging: 4, margin: 0.02,
        lineSpacingMultiple: TEXT_LINE_SPACING
      } });
      contentY += bulletHeight + 0.05;
    });
    textPlacements.forEach(function (placement) {
      slide.addText(placement.text, Object.assign({
        x: placement.bounds.x, y: placement.bounds.y, w: placement.bounds.w, h: placement.bounds.h,
      }, placement.options));
    });

    if (source.shapes) source.shapes.forEach(function (shape) {
      var options = { x: shape.x, y: shape.y, w: shape.w, h: shape.h };
      validateBounds(options, slideNumber, "shape");
      if (shape.fillColor) options.fill = { color: hexColor(shape.fillColor) };
      if (shape.lineColor || shape.lineWidth !== undefined) options.line = {
        color: hexColor(shape.lineColor || COLORS.body),
        width: shape.lineWidth === undefined ? 1 : shape.lineWidth
      };
      slide.addShape(shape.type, options);
      if (shape.text) slide.addText(shape.text, {
        x: shape.x, y: shape.y, w: shape.w, h: shape.h,
        fontFace: "Arial", fontSize: shape.fontSize || 18,
        color: hexColor(shape.color || contrastingTextColor(shape.fillColor || backgroundColor)),
        align: "center", valign: "mid", margin: 0.06
      });
    });

    var visuals = [];
    if (renderChart) visuals.push({ source: source.chart, kind: "chart" });
    if (source.table) visuals.push({ source: source.table, kind: "table" });
    if (visuals.length > 0) {
      visuals.forEach(function (visual) { validateVisualInput(visual.source, slideNumber, visual.kind); });
      var fixedHeight = visuals.reduce(function (total, visual) {
        return total + (visual.source.h === undefined ? 0 : visual.source.h);
      }, 0);
      var autoHeightCount = visuals.filter(function (visual) { return visual.source.h === undefined; }).length;
      var availableVisualHeight = SLIDE_HEIGHT - contentY - VISUAL_BOTTOM_MARGIN - VISUAL_GAP * (visualCount - 1);
      var autoVisualHeight = autoHeightCount > 0 ? (availableVisualHeight - fixedHeight) / autoHeightCount : 0;
      if (autoHeightCount > 0 && autoVisualHeight <= 0) {
        throw new Error("Slide " + slideNumber + ": no room for chart/table below the text; shorten the text or move the chart to its own slide.");
      }
      var visualY = contentY;
      visuals.forEach(function (visual, index) {
        var bounds = placeVisual(visual.source, visualY, autoVisualHeight, slideNumber, visual.kind);
        validateBounds(bounds, slideNumber, visual.kind);
        if (visual.kind === "chart") addChart(pptx, slide, visual.source, backgroundColor, textColor, bounds);
        else addTable(slide, visual.source, textColor, bounds);
        visualY = Math.max(visualY, bounds.y + bounds.h) + (index < visuals.length - 1 ? VISUAL_GAP : 0);
      });
    }

    (source.images || []).forEach(function (image) {
      validateBounds({ x: image.x, y: image.y, w: image.w, h: image.h }, slideNumber, "image");
      slide.addImage({ data: image.dataUrl, x: image.x, y: image.y, w: image.w, h: image.h, altText: image.altText || "" });
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
    pptx.theme = { headFontFace: "Cambria", bodyFontFace: "Arial", lang: "es-ES" };
    pptx.author = "Jarvys";
    pptx.subject = argumentsObject.title;
    pptx.title = argumentsObject.title;
    pptx.company = "Jarvys";
    argumentsObject.slides.forEach(function (slide, slideIndex) { addSlideContent(pptx, slide, slideIndex + 1); });

    var pptxBase64 = await pptx.write({ outputType: "base64" });
    var pptxBytes = bytesFromBase64(pptxBase64);
    var thumbnailCanvas = document.createElement("canvas");
    await global.PptxDeckRenderer.renderFirstSlide(pptxBytes, thumbnailCanvas);
    var thumbnailBase64 = thumbnailCanvas.toDataURL("image/png").split(",")[1];
    if (thumbnailBase64.length > MAX_THUMBNAIL_BASE64_CHARS) {
      throw new Error("The presentation thumbnail exceeds the displayed-card budget.");
    }
    var delivered = parseDeliveryResult(host.artifactDeliverBytes(
      pptxBase64,
      argumentsObject.title + ".pptx",
      "application/vnd.openxmlformats-officedocument.presentationml.presentation",
      argumentsObject.title,
      thumbnailBase64,
      "image/png",
      host.newArtifactDeliveryId()
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
