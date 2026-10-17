(function (global) {
  "use strict";

  // Match the host's 640x360 decode target and its existing 2x card-density margin.
  var MAX_THUMBNAIL_BASE64_CHARS = Math.ceil(1280 * 720 * 4 / 3) * 4;
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

  function placeVisual(source, y, height) {
    return {
      x: source.x === undefined ? 0.8 : source.x,
      y: source.y === undefined ? y : source.y,
      w: source.w === undefined ? 11.75 : source.w,
      h: Number.isFinite(source.h) && source.h > 0 ? source.h : height
    };
  }

  function hasChartData(chart) {
    return Boolean(chart && Array.isArray(chart.datasets) && chart.datasets.some(function (dataset) {
      return dataset && Array.isArray(dataset.values) && dataset.values.length > 0;
    }));
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
      dataLabelFormatCode: "#,##0",
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

  function addSlideContent(pptx, source) {
    var slide = pptx.addSlide();
    var backgroundColor = hexColor(source.backgroundColor || COLORS.background);
    var textColor = contrastingTextColor(backgroundColor);
    slide.background = { color: backgroundColor };

    var contentY = source.title ? 1.55 : 0.72;
    var renderChart = hasChartData(source.chart);
    var visualCount = Number(renderChart) + Number(Boolean(source.table));
    if (source.title) {
      slide.addText(source.title, {
        x: 0.65, y: 0.55, w: 12.0, h: 0.78,
        fontFace: "Cambria", fontSize: 36, bold: true,
        color: textColor, margin: 0, breakLine: false
      });
    }
    if (source.body) {
      var bodyHeight = visualCount ? 0.92 : 2.8;
      slide.addText(source.body, {
        x: 0.8, y: contentY, w: 11.7, h: bodyHeight,
        fontFace: "Arial", fontSize: 20, color: textColor,
        valign: "top", margin: 0.06
      });
      contentY += bodyHeight + 0.12;
    }
    (source.bullets || []).forEach(function (text) {
      slide.addText(text, {
        x: 1.0, y: contentY, w: 11.5, h: 0.45,
        fontFace: "Arial", fontSize: 18, color: textColor,
        bullet: { indent: 18 }, hanging: 4, margin: 0.02
      });
      contentY += 0.5;
    });

    if (source.shapes) source.shapes.forEach(function (shape) {
      var options = { x: shape.x, y: shape.y, w: shape.w, h: shape.h };
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

    var visualGap = 0.3;
    var availableVisualHeight = 7.5 - contentY - 0.55 - visualGap * (visualCount - 1);
    var visualHeight = visualCount && availableVisualHeight > 0 ? availableVisualHeight / visualCount : 0;
    if (visualHeight > 0) {
      if (renderChart) {
        addChart(pptx, slide, source.chart, backgroundColor, textColor,
          placeVisual(source.chart, contentY, visualHeight));
        contentY += visualHeight + visualGap;
      }
      if (source.table) addTable(slide, source.table, textColor,
        placeVisual(source.table, contentY, visualHeight));
    }

    (source.images || []).forEach(function (image) {
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
    argumentsObject.slides.forEach(function (slide) { addSlideContent(pptx, slide); });

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
