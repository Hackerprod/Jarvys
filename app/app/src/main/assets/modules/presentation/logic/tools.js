(function (global) {
  "use strict";

  // Match the host's 640x360 decode target and its existing 2x card-density margin.
  var MAX_THUMBNAIL_BASE64_CHARS = Math.ceil(1280 * 720 * 4 / 3) * 4;
  var SLIDE_WIDTH = 12192000 / 914400;
  var SLIDE_HEIGHT = 6858000 / 914400;
  var BOUNDS_TOLERANCE = 1 / 914400;
  var VISUAL_BOTTOM_MARGIN = 0.55;
  var VISUAL_GAP = 0.3;
  // Arial/Helvetica hhea ascent + descent + lineGap = 2355/2048, about 1.15 em.
  var TEXT_LINE_SPACING = 1.15;
  // PptxGenJS interprets margin values >= 1 as points; keep measurement in the same units.
  var BODY_TEXT_MARGIN_POINTS = 4.32;
  var BULLET_TEXT_MARGIN_POINTS = 1.44;
  var TEXT_VISUAL_GAP = 0.18;
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
  }

  async function renderFirstSlideThumbnail(pptxBytes) {
    var glimpse = await import("../vendor/pptx-glimpse.bundle.js");
    if (typeof glimpse.convertPptxToSvg !== "function") {
      throw new Error("The presentation thumbnail renderer is unavailable.");
    }
    var report = await glimpse.convertPptxToSvg(pptxBytes, { textOutput: "text", width: 1280, slides: [1] });
    var firstSlide = report && Array.isArray(report.slides) && report.slides[0];
    if (!firstSlide || typeof firstSlide.svg !== "string" || firstSlide.svg.trim().length === 0) {
      throw new Error("The presentation thumbnail renderer returned no first slide.");
    }

    var image = await new Promise(function (resolve, reject) {
      var slideImage = document.createElement("img");
      slideImage.onload = function () {
        if (slideImage.naturalWidth > 0 && slideImage.naturalHeight > 0) resolve(slideImage);
        else reject(new Error("The presentation thumbnail SVG is empty."));
      };
      slideImage.onerror = function () { reject(new Error("The presentation thumbnail SVG could not be decoded.")); };
      slideImage.src = "data:image/svg+xml;charset=utf-8," + encodeURIComponent(firstSlide.svg);
      if (slideImage.complete && slideImage.naturalWidth > 0 && slideImage.naturalHeight > 0) resolve(slideImage);
    });

    var canvas = document.createElement("canvas");
    canvas.width = 1280;
    canvas.height = 720;
    var context = canvas.getContext("2d");
    if (!context) throw new Error("A 2D canvas is unavailable for the presentation thumbnail.");
    context.drawImage(image, 0, 0, canvas.width, canvas.height);
    return canvas;
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

  function mismatchedChartDatasetIndex(chart) {
    return chart.datasets.findIndex(function (dataset) {
      if (dataset && Array.isArray(dataset.values) && dataset.values.length === 0) return false;
      return !dataset || !Array.isArray(dataset.values) || dataset.values.length !== chart.labels.length;
    });
  }

  function chartDatasetName(dataset) {
    return dataset && dataset.name ? dataset.name : "unnamed";
  }

  function validateChartValues(chart, slideNumber) {
    chart.datasets.forEach(function (dataset) {
      if (!dataset || !Array.isArray(dataset.values)) return;
      dataset.values.forEach(function (value, index) {
        if (!Number.isFinite(value)) {
          throw new Error("Slide " + slideNumber + ": chart dataset '" + chartDatasetName(dataset) + "' value at index " + index + " is not a finite number");
        }
      });
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

  function measuredTextHeight(text, width, fontSize, fontFace, marginPoints, leftIndent) {
    var marginInches = marginPoints / 72;
    var lines = measureTextLineCount(text, width - marginInches * 2, fontSize, fontFace, leftIndent || 0);
    return lines * fontSize / 72 * TEXT_LINE_SPACING + marginInches * 2;
  }

  function validateBounds(bounds, slideNumber, kind) {
    if (![bounds.x, bounds.y, bounds.w, bounds.h].every(Number.isFinite)) {
      throw new Error("Slide " + slideNumber + ": " + kind + " position and size must be finite numbers.");
    }
    if ((kind === "body text" || kind.indexOf("bullet ") === 0) && bounds.h <= 0) {
      throw new Error("Slide " + slideNumber + ": text content does not fit on the slide; shorten the body or bullets, or move visuals to another slide.");
    }
    if (kind === "shape") {
      if (bounds.w < 0 || bounds.h < 0 || (bounds.w === 0 && bounds.h === 0)) {
        throw new Error("Slide " + slideNumber + ": shape width and height must be non-negative, and at least one must be greater than zero.");
      }
    } else if (bounds.w <= 0 || bounds.h <= 0) {
      throw new Error("Slide " + slideNumber + ": " + kind + " width and height must be greater than zero.");
    }
    if (bounds.y + bounds.h > SLIDE_HEIGHT + BOUNDS_TOLERANCE && (kind === "body text" || kind.indexOf("bullet ") === 0)) {
      throw new Error("Slide " + slideNumber + ": text content does not fit on the slide; shorten the body or bullets.");
    }
    if (bounds.x < -BOUNDS_TOLERANCE || bounds.y < -BOUNDS_TOLERANCE ||
      bounds.x + bounds.w > SLIDE_WIDTH + BOUNDS_TOLERANCE || bounds.y + bounds.h > SLIDE_HEIGHT + BOUNDS_TOLERANCE) {
      throw new Error("Slide " + slideNumber + ": " + kind + " exceeds slide bounds; move it within the slide or reduce its size.");
    }
  }

  function rectanglesOverlap(first, second) {
    var overlapWidth = Math.min(first.x + first.w, second.x + second.w) - Math.max(first.x, second.x);
    var overlapHeight = Math.min(first.y + first.h, second.y + second.h) - Math.max(first.y, second.y);
    return overlapWidth > BOUNDS_TOLERANCE && overlapHeight > BOUNDS_TOLERANCE;
  }

  function automaticVisualHeight(visuals, index, y) {
    var nextExplicitYIndex = visuals.findIndex(function (visual, candidateIndex) {
      return candidateIndex > index && visual.source.y !== undefined;
    });
    var endIndex = nextExplicitYIndex === -1 ? visuals.length : nextExplicitYIndex;
    var limit = nextExplicitYIndex === -1 ? SLIDE_HEIGHT - VISUAL_BOTTOM_MARGIN : visuals[nextExplicitYIndex].source.y;
    var segment = visuals.slice(index, endIndex);
    var autoHeightCount = 0;
    var fixedHeight = 0;
    segment.forEach(function (visual) {
      if (visual.source.h === undefined) autoHeightCount += 1;
      else fixedHeight += visual.source.h;
    });
    var hasNextExplicit = nextExplicitYIndex !== -1;
    var gapCount = Math.max(0, segment.length - 1) + (hasNextExplicit ? 1 : 0);
    if (autoHeightCount === 0) return 0;
    return (limit - y - fixedHeight - VISUAL_GAP * gapCount) / autoHeightCount;
  }

  function addSlideContent(pptx, source, slideNumber) {
    if (source.chart && !hasChartData(source.chart)) {
      throw new Error("Slide " + slideNumber + ": chart has no data; add labels and at least one dataset with values.");
    }
    if (source.chart) {
      var mismatchedDatasetIndex = mismatchedChartDatasetIndex(source.chart);
      if (mismatchedDatasetIndex !== -1) {
        throw new Error("Slide " + slideNumber + ": chart dataset '" + chartDatasetName(source.chart.datasets[mismatchedDatasetIndex]) + "' has a different number of values than labels; make the lengths match.");
      }
      validateChartValues(source.chart, slideNumber);
    }
    var slide = pptx.addSlide();
    var backgroundColor = hexColor(source.backgroundColor || COLORS.background);
    var textColor = contrastingTextColor(backgroundColor);
    slide.background = { color: backgroundColor };

    var contentY = source.title ? 1.55 : 0.72;
    var renderChart = hasChartData(source.chart);
    var textPlacements = [];
    if (source.title) {
      textPlacements.push({ text: source.title, kind: "title", bounds: { x: 0.65, y: 0.55, w: 12.0, h: 0.78 }, options: {
        fontFace: "Cambria", fontSize: 36, bold: true,
        color: textColor, margin: 0, breakLine: false
      } });
    }
    if (source.body) {
      var availableBodyHeight = SLIDE_HEIGHT - contentY - VISUAL_BOTTOM_MARGIN;
      if (availableBodyHeight <= 0) {
        throw new Error("Slide " + slideNumber + ": text content does not fit on the slide; shorten the body or move visuals to another slide.");
      }
      var bodyHeight = Math.min(measuredTextHeight(source.body, 11.7, 20, "Arial", BODY_TEXT_MARGIN_POINTS, 0), availableBodyHeight);
      var bodyBounds = { x: 0.8, y: contentY, w: 11.7, h: bodyHeight };
      validateBounds(bodyBounds, slideNumber, "body text");
      textPlacements.push({ text: source.body, kind: "body text", bounds: bodyBounds, options: {
        fontFace: "Arial", fontSize: 20, color: textColor, valign: "top", margin: BODY_TEXT_MARGIN_POINTS,
        lineSpacingMultiple: TEXT_LINE_SPACING, fit: "shrink"
      } });
      contentY += bodyHeight + ((source.bullets || []).length === 0 ? TEXT_VISUAL_GAP : 0.12);
    }
    (source.bullets || []).forEach(function (text, bulletIndex) {
      var availableBulletHeight = SLIDE_HEIGHT - contentY - VISUAL_BOTTOM_MARGIN;
      if (availableBulletHeight <= 0) {
        throw new Error("Slide " + slideNumber + ": text content does not fit on the slide; shorten the body or bullets, or move visuals to another slide.");
      }
      var bulletHeight = Math.min(measuredTextHeight(text, 11.5, 18, "Arial", BULLET_TEXT_MARGIN_POINTS, 18 / 72), availableBulletHeight);
      var bulletBounds = { x: 1.0, y: contentY, w: 11.5, h: bulletHeight };
      var bulletKind = "bullet " + (bulletIndex + 1);
      validateBounds(bulletBounds, slideNumber, bulletKind);
      textPlacements.push({ text: text, kind: bulletKind, bounds: bulletBounds, options: {
        fontFace: "Arial", fontSize: 18, color: textColor,
        bullet: { indent: 18 }, hanging: 4, margin: BULLET_TEXT_MARGIN_POINTS,
        lineSpacingMultiple: TEXT_LINE_SPACING, fit: "shrink"
      } });
      contentY += bulletHeight + (bulletIndex === source.bullets.length - 1 ? TEXT_VISUAL_GAP : 0.05);
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
      var visualY = contentY;
      var placedVisuals = [];
      visuals.forEach(function (visual, index) {
        var visualStartY = visual.source.y === undefined ? visualY : visual.source.y;
        var autoHeight = visual.source.h === undefined ? automaticVisualHeight(visuals, index, visualStartY) : visual.source.h;
        if (visual.source.h === undefined && autoHeight <= 0) {
          throw new Error("Slide " + slideNumber + ": no room for the automatic " + visual.kind + " after the text and earlier visuals; shorten the text, move an earlier visual, or put this " + visual.kind + " on another slide.");
        }
        var bounds = placeVisual(visual.source, visualY, autoHeight, slideNumber, visual.kind);
        validateBounds(bounds, slideNumber, visual.kind);
        textPlacements.forEach(function (placement) {
          if (rectanglesOverlap(bounds, placement.bounds)) {
            throw new Error("Slide " + slideNumber + ": " + visual.kind + " overlaps " + placement.kind + "; move the visual below the text or shorten the text.");
          }
        });
        placedVisuals.forEach(function (placed) {
          if (rectanglesOverlap(bounds, placed.bounds)) {
            throw new Error("Slide " + slideNumber + ": " + visual.kind + " overlaps the " + placed.kind + "; move one visual to a clear area.");
          }
        });
        if (visual.kind === "chart") addChart(pptx, slide, visual.source, backgroundColor, textColor, bounds);
        else addTable(slide, visual.source, textColor, bounds);
        placedVisuals.push({ kind: visual.kind, bounds: bounds });
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
    var thumbnailCanvas = await renderFirstSlideThumbnail(pptxBytes);
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
