(function (global) {
  "use strict";

  var EMU_PER_INCH = 914400;
  var SLIDE_WIDTH = 12192000 / EMU_PER_INCH;
  var SLIDE_HEIGHT = 6858000 / EMU_PER_INCH;
  var HEX_DIGITS = "0123456789abcdefABCDEF";
  var THEME_COLORS = ["tx1", "tx2", "bg1", "bg2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6"];

  var TEXT_PROPERTIES = [
    "x", "y", "w", "h", "text", "runs", "align", "bold", "breakLine", "bullet", "color",
    "fontFace", "fontSize", "highlight", "italic", "lang", "softBreakBefore", "tabStops",
    "textDirection", "transparency", "underline", "valign", "baseline", "charSpacing", "fit",
    "fill", "flipH", "flipV", "glow", "hyperlink", "indentLevel", "isTextBox", "line",
    "lineSpacing", "lineSpacingMultiple", "margin", "outline", "paraSpaceAfter", "paraSpaceBefore",
    "placeholder", "rectRadius", "rotate", "rtlMode", "shadow", "shape", "strike", "subscript",
    "superscript", "vert", "wrap", "autoFit", "shrinkText", "inset", "lineDash", "lineHead",
    "lineSize", "lineTail", "objectName"
  ];
  var TEXT_RUN_PROPERTIES = ["text", "bold", "italic", "color", "fontSize", "fontFace", "hyperlink"];
  var SHAPE_PROPERTIES = [
    "x", "y", "w", "h", "align", "angleRange", "arcThicknessRatio", "fill", "flipH", "flipV",
    "hyperlink", "line", "points", "rectRadius", "rotate", "shadow", "lineSize", "lineDash",
    "lineHead", "lineTail", "objectName"
  ];
  var IMAGE_PROPERTIES = ["x", "y", "w", "h", "dataUrl", "sizing", "altText", "flipH", "flipV", "hyperlink", "rotate", "rounding", "shadow", "transparency", "objectName"];
  var TABLE_PROPERTIES = [
    "x", "y", "w", "h", "rows", "align", "bold", "breakLine", "bullet", "color", "fontFace",
    "fontSize", "highlight", "italic", "lang", "margin", "paraSpaceAfter", "paraSpaceBefore",
    "valign", "underline", "border", "colW", "fill", "rowH", "transparency", "rotate", "rtlMode",
    "vert", "wrap", "objectName"
  ];
  var TABLE_CELL_PROPERTIES = [
    "align", "bold", "breakLine", "bullet", "color", "fontFace", "fontSize", "highlight", "italic",
    "lang", "margin", "paraSpaceAfter", "paraSpaceBefore", "valign", "underline", "border", "fill",
    "colspan", "rowspan", "rotate", "transparency", "hyperlink", "line", "shadow", "fit", "wrap",
    "vert", "strike", "subscript", "superscript", "outline", "breakLine"
  ];
  var CHART_PROPERTIES = [
    "objectName", "axisPos", "chartColors", "chartColorsOpacity", "dataBorder",
    "displayBlanksAs", "invertedColors", "lang", "layout", "shadow", "showLabel", "showLeaderLines",
    "showLegend", "showPercent", "showSerName", "showTitle", "showValue", "v3DPerspective", "v3DRAngAx",
    "v3DRotX", "v3DRotY", "chartArea", "plotArea", "border", "fill", "catAxes", "catAxisBaseTimeUnit",
    "catAxisCrossesAt", "catAxisHidden", "catAxisLabelColor", "catAxisLabelFontBold", "catAxisLabelFontFace",
    "catAxisLabelFontItalic", "catAxisLabelFontSize", "catAxisLabelFrequency", "catAxisLabelPos",
    "catAxisLabelRotate", "catAxisLineColor", "catAxisLineShow", "catAxisLineSize", "catAxisLineStyle",
    "catAxisMajorTickMark", "catAxisMajorTimeUnit", "catAxisMajorUnit", "catAxisMaxVal", "catAxisMinorTickMark",
    "catAxisMinorTimeUnit", "catAxisMinorUnit", "catAxisMinVal", "catAxisMultiLevelLabels", "catAxisOrientation",
    "catAxisTitle", "catAxisTitleColor", "catAxisTitleFontFace", "catAxisTitleFontSize", "catAxisTitleRotate",
    "catGridLine", "catLabelFormatCode", "secondaryCatAxis", "showCatAxisTitle", "serAxes", "serAxisBaseTimeUnit",
    "serAxisHidden", "serAxisLabelColor", "serAxisLabelFontBold", "serAxisLabelFontFace", "serAxisLabelFontItalic",
    "serAxisLabelFontSize", "serAxisLabelFrequency", "serAxisLabelPos", "serAxisLineColor", "serAxisLineShow",
    "serAxisMajorTimeUnit", "serAxisMajorUnit", "serAxisMinorTimeUnit", "serAxisMinorUnit", "serAxisOrientation",
    "serAxisTitle", "serAxisTitleColor", "serAxisTitleFontFace", "serAxisTitleFontSize", "serAxisTitleRotate",
    "serGridLine", "serLabelFormatCode", "showSerAxisTitle", "secondaryValAxis", "showValAxisTitle", "valAxes",
    "valAxisCrossesAt", "valAxisDisplayUnit", "valAxisDisplayUnitLabel", "valAxisHidden", "valAxisLabelColor",
    "valAxisLabelFontBold", "valAxisLabelFontFace", "valAxisLabelFontItalic", "valAxisLabelFontSize",
    "valAxisLabelFormatCode", "valAxisLabelPos", "valAxisLabelRotate", "valAxisLineColor", "valAxisLineShow",
    "valAxisLineSize", "valAxisLineStyle", "valAxisLogScaleBase", "valAxisMajorTickMark", "valAxisMajorUnit",
    "valAxisMaxVal", "valAxisMinorTickMark", "valAxisMinVal", "valAxisOrientation", "valAxisTitle",
    "valAxisTitleColor", "valAxisTitleFontFace", "valAxisTitleFontSize", "valAxisTitleRotate", "valGridLine",
    "valLabelFormatCode", "bar3DShape", "barDir", "barGapDepthPct", "barGapWidthPct", "barGrouping",
    "barOverlapPct", "dataNoEffects", "holeSize", "lineCap", "lineDash", "lineDataSymbol",
    "lineDataSymbolLineColor", "lineDataSymbolLineSize", "lineDataSymbolSize", "lineSize", "lineSmooth",
    "firstSliceAng", "radarStyle", "dataLabelBkgrdColors", "dataLabelColor", "dataLabelFontBold",
    "dataLabelFontFace", "dataLabelFontItalic", "dataLabelFontSize", "dataLabelFormatCode", "dataLabelFormatScatter",
    "dataLabelPosition", "dataTableFontSize", "dataTableShowHorzBorder", "dataTableShowKeys",
    "dataTableShowOutline", "dataTableShowVertBorder", "legendColor", "legendFontBold", "legendFontFace",
    "legendFontItalic", "legendFontSize", "legendPos", "showDataTable", "showDataTableHorzBorder",
    "showDataTableKeys", "showDataTableOutline", "showDataTableVertBorder", "showTitle", "title", "titleColor",
    "titleFontBold", "titleFontFace", "titleFontItalic", "titleFontSize", "titlePos", "showCatName",
    "showValAxisTitle", "showSerName", "showValue", "showPercent", "showLabel"
  ];

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
    var result = await glimpse.convertPptxToSvg(pptxBytes, { textOutput: "text", width: 1280, slides: [1] });
    var firstSlide = result && Array.isArray(result.slides) && result.slides[0];
    if (!firstSlide || typeof firstSlide.svg !== "string" || firstSlide.svg.trim().length === 0) {
      throw new Error("The presentation thumbnail renderer returned no first slide.");
    }
    var image = await decodeImage("data:image/svg+xml;charset=utf-8," + encodeURIComponent(firstSlide.svg));
    var canvas = document.createElement("canvas");
    canvas.width = 1280;
    canvas.height = 720;
    var context = canvas.getContext("2d");
    if (!context) throw new Error("A 2D canvas is unavailable for the presentation thumbnail.");
    context.drawImage(image, 0, 0, canvas.width, canvas.height);
    return canvas;
  }

  function decodeImage(dataUrl) {
    return new Promise(function (resolve, reject) {
      var image = document.createElement("img");
      image.onload = function () {
        if (image.naturalWidth > 0 && image.naturalHeight > 0) resolve(image);
        else reject(new Error("Image data could not be decoded."));
      };
      image.onerror = function () { reject(new Error("Image data could not be decoded.")); };
      image.src = dataUrl;
      if (image.complete && image.naturalWidth > 0 && image.naturalHeight > 0) resolve(image);
    });
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

  function fail(message) { throw new Error(message); }

  function objectValue(value, context, field) {
    if (!value || typeof value !== "object" || Array.isArray(value)) fail(context + ": " + field + " must be an object.");
    return value;
  }

  function allowProperties(value, allowed, context, field) {
    Object.keys(value).forEach(function (key) {
      if (allowed.indexOf(key) === -1) fail(context + ": unsupported property " + field + "." + key + ".");
    });
  }

  function requireType(value, expected, context, field) {
    if (typeof value !== expected) fail(context + ": " + field + " must be a " + expected + ".");
  }

  function isHexColor(value) {
    if (typeof value !== "string" || value.length !== 6) return false;
    for (var index = 0; index < value.length; index += 1) {
      if (HEX_DIGITS.indexOf(value.charAt(index)) === -1) return false;
    }
    return true;
  }

  function validateColor(value, context, field) {
    requireType(value, "string", context, field);
    if (!isHexColor(value) && THEME_COLORS.indexOf(value) === -1) {
      fail(context + ": " + field + " must be a PptxGenJS hex color or theme color.");
    }
  }

  function validateNumber(value, context, field, positive) {
    if (typeof value !== "number" || !Number.isFinite(value)) fail(context + ": " + field + " must be a finite number.");
    if (positive && value <= 0) fail(context + ": " + field + " must be greater than zero.");
  }

  function validateMargin(value, context, field) {
    if (typeof value === "number") {
      validateNumber(value, context, field, false);
      return;
    }
    if (!Array.isArray(value) || value.length !== 4) fail(context + ": " + field + " must be a number or a four-value array.");
    value.forEach(function (part, index) { validateNumber(part, context, field + "[" + index + "]", false); });
  }

  function validateFillOrLine(value, context, field, line) {
    objectValue(value, context, field);
    var fields = line
      ? ["color", "transparency", "type", "alpha", "width", "dashType", "beginArrowType", "endArrowType", "lineDash", "lineHead", "lineTail", "pt", "size"]
      : ["color", "transparency", "type", "alpha"];
    allowProperties(value, fields, context, field);
    if (value.color !== undefined) validateColor(value.color, context, field + ".color");
    ["transparency", "alpha", "width", "pt", "size"].forEach(function (key) {
      if (value[key] !== undefined) validateNumber(value[key], context, field + "." + key, false);
    });
    ["type", "dashType", "beginArrowType", "endArrowType", "lineDash", "lineHead", "lineTail"].forEach(function (key) {
      if (value[key] !== undefined) requireType(value[key], "string", context, field + "." + key);
    });
    if (value.type !== undefined && (line ? ["none", "dash", "solid"].indexOf(value.type) === -1 : ["none", "solid"].indexOf(value.type) === -1)) {
      fail(context + ": " + field + ".type has an unsupported PptxGenJS value.");
    }
    if (value.dashType !== undefined && ["solid", "dash", "dashDot", "lgDash", "lgDashDot", "lgDashDotDot", "sysDash", "sysDot"].indexOf(value.dashType) === -1) {
      fail(context + ": " + field + ".dashType has an unsupported PptxGenJS value.");
    }
    ["beginArrowType", "endArrowType", "lineHead", "lineTail"].forEach(function (key) {
      if (value[key] !== undefined && ["none", "arrow", "diamond", "oval", "stealth", "triangle"].indexOf(value[key]) === -1) {
        fail(context + ": " + field + "." + key + " has an unsupported PptxGenJS value.");
      }
    });
  }

  function validateHyperlink(value, context, field) {
    objectValue(value, context, field);
    allowProperties(value, ["slide", "url", "tooltip"], context, field);
    if (value.slide !== undefined) validateNumber(value.slide, context, field + ".slide", true);
    ["url", "tooltip"].forEach(function (key) {
      if (value[key] !== undefined) requireType(value[key], "string", context, field + "." + key);
    });
  }

  function validateTextStyle(style, allowed, context, field) {
    objectValue(style, context, field);
    allowProperties(style, allowed, context, field);
    ["fontFace", "lang", "highlight", "placeholder", "objectName", "textDirection", "vert", "lineDash", "lineHead", "lineTail", "valign", "align", "fit"].forEach(function (key) {
      if (style[key] !== undefined) requireType(style[key], "string", context, field + "." + key);
    });
    ["bold", "italic", "breakLine", "softBreakBefore", "flipH", "flipV", "isTextBox", "rtlMode", "wrap", "autoFit", "shrinkText", "subscript", "superscript", "strike"].forEach(function (key) {
      if (style[key] !== undefined && typeof style[key] !== "boolean" && !(key === "strike" && typeof style[key] === "string")) {
        fail(context + ": " + field + "." + key + " has an invalid type.");
      }
    });
    ["fontSize", "lineSpacing", "lineSpacingMultiple", "paraSpaceBefore", "paraSpaceAfter", "charSpacing", "indentLevel", "baseline", "rotate", "transparency", "rectRadius", "lineSize", "inset"].forEach(function (key) {
      if (style[key] !== undefined) validateNumber(style[key], context, field + "." + key, false);
    });
    ["colspan", "rowspan"].forEach(function (key) {
      if (style[key] !== undefined) {
        validateNumber(style[key], context, field + "." + key, true);
        if (!Number.isInteger(style[key])) fail(context + ": " + field + "." + key + " must be an integer.");
      }
    });
    if (style.fontSize !== undefined && style.fontSize <= 0) fail(context + ": " + field + ".fontSize must be greater than zero.");
    if (style.color !== undefined) validateColor(style.color, context, field + ".color");
    if (style.fill !== undefined) validateFillOrLine(style.fill, context, field + ".fill", false);
    if (style.line !== undefined) validateFillOrLine(style.line, context, field + ".line", true);
    if (style.margin !== undefined) validateMargin(style.margin, context, field + ".margin");
    if (style.hyperlink !== undefined) validateHyperlink(style.hyperlink, context, field + ".hyperlink");
    if (style.fit !== undefined && ["none", "shrink", "resize"].indexOf(style.fit) === -1) fail(context + ": " + field + ".fit has an unsupported value.");
    if (style.align !== undefined && ["left", "center", "right", "justify"].indexOf(style.align) === -1) fail(context + ": " + field + ".align has an unsupported value.");
    if (style.valign !== undefined && ["top", "middle", "bottom"].indexOf(style.valign) === -1) fail(context + ": " + field + ".valign has an unsupported value.");
    if (style.bullet !== undefined) validateBullet(style.bullet, context, field + ".bullet");
    if (style.underline !== undefined && typeof style.underline !== "string" && typeof style.underline !== "object") fail(context + ": " + field + ".underline has an invalid type.");
    if (style.underline && typeof style.underline === "object") {
      allowProperties(style.underline, ["style", "color"], context, field + ".underline");
      if (style.underline.color !== undefined) validateColor(style.underline.color, context, field + ".underline.color");
      if (style.underline.style !== undefined) requireType(style.underline.style, "string", context, field + ".underline.style");
    }
    if (style.outline !== undefined) {
      objectValue(style.outline, context, field + ".outline");
      allowProperties(style.outline, ["color", "size"], context, field + ".outline");
      if (style.outline.color !== undefined) validateColor(style.outline.color, context, field + ".outline.color");
      if (style.outline.size !== undefined) validateNumber(style.outline.size, context, field + ".outline.size", false);
    }
    if (style.tabStops !== undefined) {
      if (!Array.isArray(style.tabStops)) fail(context + ": " + field + ".tabStops must be an array.");
      style.tabStops.forEach(function (stop, index) {
        objectValue(stop, context, field + ".tabStops[" + index + "]");
        allowProperties(stop, ["position", "alignment"], context, field + ".tabStops[" + index + "]");
        validateNumber(stop.position, context, field + ".tabStops[" + index + "].position", false);
        if (stop.alignment !== undefined && ["l", "r", "ctr", "dec"].indexOf(stop.alignment) === -1) fail(context + ": " + field + ".tabStops[" + index + "].alignment has an unsupported value.");
      });
    }
  }

  function validateBullet(value, context, field) {
    if (typeof value === "boolean") return;
    objectValue(value, context, field);
    allowProperties(value, ["type", "characterCode", "indent", "numberType", "numberStartAt", "code", "marginPt", "startAt", "style"], context, field);
    ["type", "characterCode", "numberType", "code", "style"].forEach(function (key) {
      if (value[key] !== undefined) requireType(value[key], "string", context, field + "." + key);
    });
    ["indent", "numberStartAt", "marginPt", "startAt"].forEach(function (key) {
      if (value[key] !== undefined) validateNumber(value[key], context, field + "." + key, false);
    });
  }

  function validateCoordinates(element, context, field, allowMissingHeight, allowLineZero) {
    ["x", "y", "w"].forEach(function (key) { validateNumber(element[key], context, field + "." + key, false); });
    if (element.h === undefined && allowMissingHeight) {
      if (element.w <= 0) fail(context + ": " + field + ".w must be greater than zero.");
      if (element.x < 0 || element.y < 0 || element.x + element.w > SLIDE_WIDTH || element.y > SLIDE_HEIGHT) {
        fail(context + ": " + field + " is outside the slide bounds (" + SLIDE_WIDTH + " × " + SLIDE_HEIGHT + " in).");
      }
      return;
    }
    validateNumber(element.h, context, field + ".h", false);
    if (element.w < 0 || element.h < 0 || (!allowLineZero && (element.w === 0 || element.h === 0)) || (allowLineZero && element.w === 0 && element.h === 0)) {
      fail(context + ": " + field + ".w and .h must be positive; a line may have one zero dimension.");
    }
    if (element.x < 0 || element.y < 0 || element.x + element.w > SLIDE_WIDTH || element.y + element.h > SLIDE_HEIGHT) {
      fail(context + ": " + field + " is outside the slide bounds (" + SLIDE_WIDTH + " × " + SLIDE_HEIGHT + " in).");
    }
  }

  function validateChartOptions(options, context, field) {
    objectValue(options, context, field);
    allowProperties(options, CHART_PROPERTIES, context, field);
    Object.keys(options).forEach(function (key) {
      var value = options[key];
      if (typeof value === "number") validateNumber(value, context, field + "." + key, false);
      if (key.indexOf("FontSize") !== -1 && typeof value === "number" && value <= 0) fail(context + ": " + field + "." + key + " must be greater than zero.");
      if (["showLabel", "showLeaderLines", "showLegend", "showPercent", "showSerName", "showTitle", "showValue", "v3DRAngAx", "catAxisHidden", "catAxisLabelFontBold", "catAxisLabelFontItalic", "catAxisLineShow", "catAxisMultiLevelLabels", "showCatAxisTitle", "serAxisHidden", "serAxisLabelFontBold", "serAxisLabelFontItalic", "serAxisLineShow", "showSerAxisTitle", "secondaryValAxis", "secondaryCatAxis", "showValAxisTitle", "valAxisHidden", "valAxisLabelFontBold", "valAxisLabelFontItalic", "valAxisLineShow", "dataNoEffects", "lineSmooth", "dataLabelFontBold", "dataLabelFontItalic", "dataTableShowHorzBorder", "dataTableShowKeys", "dataTableShowOutline", "dataTableShowVertBorder", "legendFontBold", "legendFontItalic", "showDataTable", "showDataTableHorzBorder", "showDataTableKeys", "showDataTableOutline", "showDataTableVertBorder", "titleFontBold", "titleFontItalic", "showCatName"].indexOf(key) !== -1 && typeof value !== "boolean") {
        fail(context + ": " + field + "." + key + " must be a boolean.");
      }
      if (["axisPos", "objectName", "displayBlanksAs", "lang", "catAxisBaseTimeUnit", "catAxisLabelPos", "catAxisLineStyle", "catAxisMajorTickMark", "catAxisMajorTimeUnit", "catAxisMinorTickMark", "catAxisMinorTimeUnit", "catAxisOrientation", "catAxisTitle", "catLabelFormatCode", "serAxisBaseTimeUnit", "serAxisLabelPos", "serAxisMajorTimeUnit", "serAxisMinorTimeUnit", "serAxisOrientation", "serAxisTitle", "serLabelFormatCode", "valAxisDisplayUnit", "valAxisDisplayUnitLabel", "valAxisLabelFormatCode", "valAxisLabelPos", "valAxisLineStyle", "valAxisMajorTickMark", "valAxisOrientation", "valAxisTitle", "valLabelFormatCode", "bar3DShape", "barDir", "barGrouping", "lineCap", "lineDash", "lineDataSymbol", "radarStyle", "dataLabelFormatCode", "dataLabelFormatScatter", "dataLabelPosition", "legendPos", "title", "titleFontFace", "titlePos", "legendFontFace", "catAxisLabelFontFace", "catAxisLineColor", "catAxisTitleFontFace", "serAxisLabelFontFace", "serAxisLineColor", "serAxisTitleFontFace", "valAxisLabelFontFace", "valAxisLineColor", "valAxisTitleFontFace"].indexOf(key) !== -1 && typeof value !== "string") {
        fail(context + ": " + field + "." + key + " must be a string.");
      }
      if (["titleColor", "legendColor", "catAxisLabelColor", "catAxisTitleColor", "serAxisLabelColor", "serAxisTitleColor", "valAxisLabelColor", "valAxisTitleColor", "lineDataSymbolLineColor", "dataLabelColor"].indexOf(key) !== -1) validateColor(value, context, field + "." + key);
      if (key === "chartColors" || key === "invertedColors") {
        if (!Array.isArray(value)) fail(context + ": " + field + "." + key + " must be an array.");
        value.forEach(function (color, index) {
          if (!isHexColor(color)) fail(context + ": " + field + "." + key + "[" + index + "] must be a six-character hex color.");
        });
      }
      if (["catAxes", "serAxes", "valAxes"].indexOf(key) !== -1) {
        if (!Array.isArray(value)) fail(context + ": " + field + "." + key + " must be an array.");
        value.forEach(function (axis, index) { objectValue(axis, context, field + "." + key + "[" + index + "]"); });
      }
      if (key === "dataLabelBkgrdColors") {
        if (!Array.isArray(value)) fail(context + ": " + field + "." + key + " must be an array.");
        value.forEach(function (color, index) { validateColor(color, context, field + "." + key + "[" + index + "]"); });
      }
      if (["catGridLine", "valGridLine", "serGridLine"].indexOf(key) !== -1 && value !== null) {
        objectValue(value, context, field + "." + key);
        allowProperties(value, ["cap", "color", "size", "style"], context, field + "." + key);
        if (value.color !== undefined) validateColor(value.color, context, field + "." + key + ".color");
        if (value.size !== undefined) validateNumber(value.size, context, field + "." + key + ".size", false);
        if (value.style !== undefined && ["solid", "dash", "dot", "none"].indexOf(value.style) === -1) fail(context + ": " + field + "." + key + ".style has an unsupported PptxGenJS value.");
        if (value.cap !== undefined && ["flat", "square", "round"].indexOf(value.cap) === -1) fail(context + ": " + field + "." + key + ".cap has an unsupported PptxGenJS value.");
      }
      if (key === "fill" && typeof value === "string") validateColor(value, context, field + "." + key);
      if (key === "fill" && value && typeof value === "object") validateFillOrLine(value, context, field + "." + key, false);
      if (["border", "dataBorder"].indexOf(key) !== -1 && value && typeof value === "object") validateBorder(value, context, field + "." + key);
      if (["plotArea", "chartArea"].indexOf(key) !== -1 && value && typeof value === "object") {
        objectValue(value, context, field + "." + key);
        allowProperties(value, ["border", "fill", "roundedCorners"], context, field + "." + key);
        if (value.fill) validateFillOrLine(value.fill, context, field + "." + key + ".fill", false);
        if (value.border) validateBorder(value.border, context, field + "." + key + ".border");
        if (value.roundedCorners !== undefined && typeof value.roundedCorners !== "boolean") fail(context + ": " + field + "." + key + ".roundedCorners must be a boolean.");
      }
      if (["layout"].indexOf(key) !== -1 && value && typeof value === "object") {
        objectValue(value, context, field + ".layout");
        allowProperties(value, ["x", "y", "w", "h"], context, field + ".layout");
        Object.keys(value).forEach(function (coordinate) {
          validateNumber(value[coordinate], context, field + ".layout." + coordinate, false);
          if (value[coordinate] < 0 || value[coordinate] > 1) fail(context + ": " + field + ".layout." + coordinate + " must be between zero and one.");
        });
      }
    });
  }

  function validateBorder(value, context, field) {
    if (Array.isArray(value)) {
      if (value.length !== 4) fail(context + ": " + field + " must contain four border values.");
      value.forEach(function (border, index) { if (border) validateBorder(border, context, field + "[" + index + "]"); });
      return;
    }
    objectValue(value, context, field);
    allowProperties(value, ["type", "color", "pt"], context, field);
    if (value.type !== undefined) requireType(value.type, "string", context, field + ".type");
    if (value.color !== undefined) validateColor(value.color, context, field + ".color");
    if (value.pt !== undefined) validateNumber(value.pt, context, field + ".pt", false);
  }

  function validateTableRows(rows, context) {
    if (!Array.isArray(rows) || rows.length === 0) fail(context + ": rows must be a non-empty array.");
    var columns = null;
    var occupiedUntil = [];
    rows.forEach(function (row, rowIndex) {
      if (!Array.isArray(row)) fail(context + ": rows[" + rowIndex + "] must be an array.");
      var cursor = 0;
      row.forEach(function (cell, cellIndex) {
        var cellField = "rows[" + rowIndex + "][" + cellIndex + "]";
        var options = {};
        if (typeof cell !== "string") {
          objectValue(cell, context, cellField);
          allowProperties(cell, ["text", "options"], context, cellField);
          if (typeof cell.text !== "string" && !Array.isArray(cell.text)) fail(context + ": " + cellField + ".text must be a string or rich-text array.");
          if (Array.isArray(cell.text)) {
            if (cell.text.length === 0) fail(context + ": " + cellField + ".text must contain text runs.");
            cell.text.forEach(function (run, runIndex) {
              var runField = cellField + ".text[" + runIndex + "]";
              objectValue(run, context, runField);
              allowProperties(run, ["text", "options"], context, runField);
              requireType(run.text, "string", context, runField + ".text");
              if (run.options !== undefined) validateTextStyle(run.options, TABLE_CELL_PROPERTIES, context, runField + ".options");
            });
          }
          if (cell.options !== undefined) {
            options = cell.options;
            validateTextStyle(options, TABLE_CELL_PROPERTIES, context, cellField + ".options");
          }
        }
        var colspan = options.colspan === undefined ? 1 : options.colspan;
        var rowspan = options.rowspan === undefined ? 1 : options.rowspan;
        while (true) {
          while (occupiedUntil[cursor] > rowIndex) cursor += 1;
          var covered = false;
          for (var column = cursor; column < cursor + colspan; column += 1) {
            if (occupiedUntil[column] > rowIndex) { covered = true; break; }
          }
          if (!covered) break;
          cursor += 1;
        }
        for (var spanColumn = cursor; spanColumn < cursor + colspan; spanColumn += 1) {
          occupiedUntil[spanColumn] = Math.max(occupiedUntil[spanColumn] || 0, rowIndex + rowspan);
        }
        cursor += colspan;
      });
      var logicalColumns = cursor;
      for (var occupiedColumn = 0; occupiedColumn < occupiedUntil.length; occupiedColumn += 1) {
        if (occupiedUntil[occupiedColumn] > rowIndex) logicalColumns = Math.max(logicalColumns, occupiedColumn + 1);
      }
      if (logicalColumns === 0) fail(context + ": rows[" + rowIndex + "] does not define any table columns.");
      if (columns === null) columns = logicalColumns;
      if (logicalColumns !== columns) fail(context + ": rows[" + rowIndex + "] resolves to " + logicalColumns + " logical columns; expected " + columns + ".");
    });
    return columns;
  }

  function validateImageDataUrl(image, context, index) {
    if (typeof image.dataUrl !== "string" || image.dataUrl.indexOf("data:image/") !== 0) {
      fail(context + ": dataUrl must be an image data URL.");
    }
    var comma = image.dataUrl.indexOf(",");
    if (comma === -1 || image.dataUrl.slice(0, comma).indexOf(";base64") === -1) {
      fail(context + ": dataUrl must use the base64 image data form supported by PptxGenJS.");
    }
    return decodeImage(image.dataUrl).catch(function () {
      fail(context + ": dataUrl image data cannot be decoded.");
    });
  }

  function validateTheme(theme) {
    if (theme === undefined) return;
    objectValue(theme, "Presentation", "theme");
    allowProperties(theme, ["fonts", "colors", "background"], "Presentation", "theme");
    if (theme.fonts !== undefined) {
      objectValue(theme.fonts, "Presentation", "theme.fonts");
      allowProperties(theme.fonts, ["headFontFace", "bodyFontFace"], "Presentation", "theme.fonts");
      Object.keys(theme.fonts).forEach(function (key) { requireType(theme.fonts[key], "string", "Presentation", "theme.fonts." + key); });
    }
    if (theme.colors !== undefined) {
      objectValue(theme.colors, "Presentation", "theme.colors");
      Object.keys(theme.colors).forEach(function (key) {
        if (!isHexColor(theme.colors[key])) fail("Presentation: theme.colors." + key + " must be a six-character PptxGenJS hex color.");
      });
    }
    if (theme.background !== undefined) validateBackground(theme.background, "Presentation", "theme.background");
  }

  function validateBackground(background, context, field) {
    if (typeof background === "string") {
      validateColor(background, context, field);
      return;
    }
    objectValue(background, context, field);
    allowProperties(background, ["color"], context, field);
    validateColor(background.color, context, field + ".color");
  }

  function elementContext(slideNumber, elementIndex, elementType) {
    return "Slide " + slideNumber + ", element " + elementIndex + " (" + String(elementType) + ")";
  }

  function validatePresentationInput(args, pptx) {
    objectValue(args, "Presentation", "arguments");
    allowProperties(args, ["title", "theme", "slides"], "Presentation", "arguments");
    requireType(args.title, "string", "Presentation", "title");
    if (!Array.isArray(args.slides) || args.slides.length === 0) fail("Presentation: slides must contain at least one slide.");
    validateTheme(args.theme);
    var imageDecodes = [];
    args.slides.forEach(function (source, slideIndex) {
      var slideNumber = slideIndex + 1;
      objectValue(source, "Slide " + slideNumber, "slide");
      allowProperties(source, ["background", "notes", "elements"], "Slide " + slideNumber, "slide");
      if (source.background !== undefined) validateBackground(source.background, "Slide " + slideNumber, "background");
      if (source.notes !== undefined && typeof source.notes !== "string" && !Array.isArray(source.notes)) fail("Slide " + slideNumber + ": notes must be a string or an array of strings.");
      if (Array.isArray(source.notes)) source.notes.forEach(function (note, noteIndex) { requireType(note, "string", "Slide " + slideNumber, "notes[" + noteIndex + "]"); });
      if (!Array.isArray(source.elements)) fail("Slide " + slideNumber + ": elements must be an array.");
      source.elements.forEach(function (element, elementIndex) {
        objectValue(element, "Slide " + slideNumber + ", element " + elementIndex, "element");
        var type = element.type;
        var context = elementContext(slideNumber, elementIndex, type);
        if (["text", "shape", "image", "chart", "table"].indexOf(type) === -1) fail(context + ": type is unknown.");
        if (type === "text") {
          allowProperties(element, TEXT_PROPERTIES.concat(["type"]), context, "element");
          validateCoordinates(element, context, "element", false, false);
          if ((element.text === undefined) === (element.runs === undefined)) fail(context + ": provide exactly one of text or runs.");
          if (element.text !== undefined) requireType(element.text, "string", context, "text");
          if (element.runs !== undefined) {
            if (!Array.isArray(element.runs) || element.runs.length === 0) fail(context + ": runs must be a non-empty array.");
            element.runs.forEach(function (run, runIndex) {
              var runContext = context + ", run " + runIndex;
              objectValue(run, runContext, "run");
              allowProperties(run, TEXT_RUN_PROPERTIES, runContext, "run");
              requireType(run.text, "string", runContext, "text");
              validateTextStyle(run, TEXT_RUN_PROPERTIES, runContext, "run");
            });
          }
          var textStyle = Object.assign({}, element);
          ["type", "x", "y", "w", "h", "text", "runs"].forEach(function (key) { delete textStyle[key]; });
          validateTextStyle(textStyle, TEXT_PROPERTIES.filter(function (key) { return ["type", "x", "y", "w", "h", "text", "runs"].indexOf(key) === -1; }), context, "element");
        } else if (type === "shape") {
          allowProperties(element, SHAPE_PROPERTIES.concat(["type", "shape"]), context, "element");
          validateCoordinates(element, context, "element", false, true);
          if (typeof element.shape !== "string" || Object.values(pptx.ShapeType).indexOf(element.shape) === -1) fail(context + ": shape must name a PptxGenJS ShapeType.");
          ["fill", "line"].forEach(function (key) { if (element[key] !== undefined) validateFillOrLine(element[key], context, key, key === "line"); });
          ["align", "objectName"].forEach(function (key) { if (element[key] !== undefined) requireType(element[key], "string", context, key); });
          if (element.rotate !== undefined) validateNumber(element.rotate, context, "rotate", false);
          if (element.shadow !== undefined) validateShadow(element.shadow, context, "shadow");
          if (element.hyperlink !== undefined) validateHyperlink(element.hyperlink, context, "hyperlink");
        } else if (type === "image") {
          allowProperties(element, IMAGE_PROPERTIES.concat(["type"]), context, "element");
          validateCoordinates(element, context, "element", false, false);
          requireType(element.altText === undefined ? "" : element.altText, "string", context, "altText");
          if (element.sizing !== undefined) validateImageSizing(element.sizing, context);
          if (element.hyperlink !== undefined) validateHyperlink(element.hyperlink, context, "hyperlink");
          imageDecodes.push(validateImageDataUrl(element, context, elementIndex));
        } else if (type === "chart") {
          allowProperties(element, ["type", "chartType", "labels", "datasets", "x", "y", "w", "h", "options"], context, "element");
          validateCoordinates(element, context, "element", false, false);
          if (typeof element.chartType !== "string" || Object.values(pptx.ChartType).indexOf(element.chartType) === -1) fail(context + ": chartType is not a supported PptxGenJS chart type.");
          if (element.chartType === "scatter") fail(context + ": chartType scatter is not supported by the labels and numeric values input format; use another chart type.");
          if (!Array.isArray(element.labels) || element.labels.length === 0) fail(context + ": labels must contain chart data.");
          element.labels.forEach(function (label, labelIndex) { requireType(label, "string", context, "labels[" + labelIndex + "]"); });
          if (!Array.isArray(element.datasets) || element.datasets.length === 0) fail(context + ": datasets must contain chart data.");
          element.datasets.forEach(function (dataset, datasetIndex) {
            var datasetField = "datasets[" + datasetIndex + "]";
            objectValue(dataset, context, datasetField);
            allowProperties(dataset, ["name", "values", "color"], context, datasetField);
            requireType(dataset.name, "string", context, datasetField + ".name");
            if (dataset.color !== undefined && !isHexColor(dataset.color)) fail(context + ": " + datasetField + ".color must be a six-character hex color.");
            if (!Array.isArray(dataset.values) || dataset.values.length === 0) fail(context + ": " + datasetField + ".values must contain chart data.");
            if (dataset.values.length !== element.labels.length) fail(context + ": " + datasetField + ".values has " + dataset.values.length + " entries but labels has " + element.labels.length + ".");
            dataset.values.forEach(function (value, valueIndex) { validateNumber(value, context, datasetField + ".values[" + valueIndex + "]", false); });
          });
          if (element.options !== undefined) validateChartOptions(element.options, context, "options");
        } else if (type === "table") {
          allowProperties(element, TABLE_PROPERTIES.concat(["type"]), context, "element");
          validateCoordinates(element, context, "element", true, false);
          var columnCount = validateTableRows(element.rows, context);
          if (element.colW !== undefined) {
            if (typeof element.colW === "number") validateNumber(element.colW, context, "colW", true);
            else if (!Array.isArray(element.colW) || element.colW.length !== columnCount) fail(context + ": colW must be a positive number or one width per table column.");
            else element.colW.forEach(function (width, widthIndex) { validateNumber(width, context, "colW[" + widthIndex + "]", true); });
          }
          if (element.rowH !== undefined) {
            if (typeof element.rowH === "number") validateNumber(element.rowH, context, "rowH", true);
            else if (!Array.isArray(element.rowH) || element.rowH.length !== element.rows.length) fail(context + ": rowH must be a positive number or one height per table row.");
            else element.rowH.forEach(function (height, heightIndex) { validateNumber(height, context, "rowH[" + heightIndex + "]", true); });
          }
          ["fontFace", "objectName"].forEach(function (key) { if (element[key] !== undefined) requireType(element[key], "string", context, key); });
          if (element.fontSize !== undefined) validateNumber(element.fontSize, context, "fontSize", true);
          if (element.color !== undefined) validateColor(element.color, context, "color");
          if (element.fill !== undefined) validateFillOrLine(element.fill, context, "fill", false);
          if (element.border !== undefined) validateBorder(element.border, context, "border");
          if (element.margin !== undefined) validateMargin(element.margin, context, "margin");
        }
      });
    });
    return Promise.all(imageDecodes);
  }

  function validateShadow(shadow, context, field) {
    objectValue(shadow, context, field);
    allowProperties(shadow, ["type", "opacity", "blur", "angle", "offset", "color", "rotateWithShape"], context, field);
    if (shadow.type !== undefined && ["outer", "inner", "none"].indexOf(shadow.type) === -1) fail(context + ": " + field + ".type has an unsupported value.");
    ["opacity", "blur", "angle", "offset"].forEach(function (key) { if (shadow[key] !== undefined) validateNumber(shadow[key], context, field + "." + key, false); });
    if (shadow.color !== undefined) validateColor(shadow.color, context, field + ".color");
    if (shadow.rotateWithShape !== undefined && typeof shadow.rotateWithShape !== "boolean") fail(context + ": " + field + ".rotateWithShape must be a boolean.");
  }

  function validateImageSizing(sizing, context) {
    if (typeof sizing === "string") {
      if (["cover", "contain"].indexOf(sizing) === -1) fail(context + ": sizing must be cover or contain.");
      return;
    }
    objectValue(sizing, context, "sizing");
    allowProperties(sizing, ["type", "x", "y", "w", "h"], context, "sizing");
    if (["cover", "contain", "crop"].indexOf(sizing.type) === -1) fail(context + ": sizing.type is unsupported by PptxGenJS.");
    ["w", "h"].forEach(function (key) { validateNumber(sizing[key], context, "sizing." + key, true); });
    ["x", "y"].forEach(function (key) { if (sizing[key] !== undefined) validateNumber(sizing[key], context, "sizing." + key, false); });
  }

  function validateSlidesForGeneration(args, pptx) {
    return validatePresentationInput(args, pptx);
  }

  function colorValue(color) {
    if (typeof color !== "string" || !isHexColor(color)) return null;
    return color;
  }

  function relativeLuminance(color) {
    var value = colorValue(color);
    if (!value) return null;
    function channel(offset) {
      var srgb = parseInt(value.slice(offset, offset + 2), 16) / 255;
      return srgb <= 0.04045 ? srgb / 12.92 : Math.pow((srgb + 0.055) / 1.055, 2.4);
    }
    return 0.2126 * channel(0) + 0.7152 * channel(2) + 0.0722 * channel(4);
  }

  function contrastRatio(foreground, background) {
    var first = relativeLuminance(foreground);
    var second = relativeLuminance(background);
    if (first === null || second === null) return { known: false };
    var lighter = Math.max(first, second);
    var darker = Math.min(first, second);
    return { known: true, ratio: (lighter + 0.05) / (darker + 0.05) };
  }

  function backgroundColor(background) {
    if (typeof background === "string") return background;
    return background && background.color;
  }

  function marginPoints(value) {
    if (value === undefined) return [7.2, 7.2, 3.6, 3.6];
    if (typeof value === "number") return [value, value, value, value];
    return value;
  }

  function bulletIndentPoints(element) {
    if (!element.bullet) return 0;
    var indent = typeof element.bullet === "object" && element.bullet.indent ? element.bullet.indent : 27;
    var level = typeof element.indentLevel === "number" && element.indentLevel > 0 ? element.indentLevel : 0;
    return indent * (level ? 1 + level : 1);
  }

  function fontCss(fontSizePt, fontFace) {
    return (fontSizePt * 96 / 72) + "px " + (fontFace || "Calibri");
  }

  function textRuns(element) {
    if (element.runs) return element.runs;
    return [{ text: element.text, fontSize: element.fontSize, fontFace: element.fontFace, bold: element.bold, italic: element.italic, color: element.color }];
  }

  function flattenText(element) {
    return textRuns(element).map(function (run) { return run.text; }).join("");
  }

  function fillBackground(fill) {
    if (fill === undefined || fill === null || fill.type === "none") return null;
    if (typeof fill !== "object") return { color: null, reason: "background is unknown" };
    if (fill.transparency !== undefined && fill.transparency !== 0) return { color: null, reason: "background is transparent/unknown" };
    if (fill.alpha !== undefined) return { color: null, reason: "background is transparent/unknown" };
    if (fill.color !== undefined) return { color: fill.color, reason: null };
    return { color: null, reason: "background is a gradient/unknown" };
  }

  function coversMostOfBox(candidate, target) {
    var left = Math.max(candidate.x, target.x);
    var top = Math.max(candidate.y, target.y);
    var right = Math.min(candidate.x + candidate.w, target.x + target.w);
    var bottom = Math.min(candidate.y + candidate.h, target.y + target.h);
    var intersectionArea = Math.max(0, right - left) * Math.max(0, bottom - top);
    var targetArea = target.w * target.h;
    var contains = candidate.x <= target.x && candidate.y <= target.y &&
      candidate.x + candidate.w >= target.x + target.w && candidate.y + candidate.h >= target.y + target.h;
    return contains || (targetArea > 0 && intersectionArea > targetArea / 2);
  }

  function effectiveTextBackground(source, args, element, elementIndex) {
    if (element.fill !== undefined && element.fill !== null && element.fill.type !== "none") {
      return fillBackground(element.fill) || { color: null, reason: "background is transparent/unknown" };
    }
    var textBox = { x: element.x, y: element.y, w: element.w, h: element.h };
    for (var previousIndex = elementIndex - 1; previousIndex >= 0; previousIndex -= 1) {
      var candidate = source.elements[previousIndex];
      if ((candidate.type !== "shape" && candidate.type !== "image") || !coversMostOfBox(candidate, textBox)) continue;
      if (candidate.type === "image") return { color: null, reason: "background is an image/unknown" };
      if (candidate.fill === undefined || candidate.fill === null || candidate.fill.type === "none") continue;
      return fillBackground(candidate.fill) || { color: null, reason: "background is transparent/unknown" };
    }
    var slideBackground = source.background !== undefined
      ? source.background
      : (args.theme && args.theme.background !== undefined ? args.theme.background : "FFFFFF");
    var color = backgroundColor(slideBackground);
    return color === undefined || color === null
      ? { color: null, reason: "background is unknown" }
      : { color: color, reason: null };
  }

  function textContrast(element, background) {
    if (background.reason) return { contrast: null, reason: background.reason, low: false };
    var runContrasts = textRuns(element).map(function (run, runIndex) {
      var foreground = run.color || element.color || "000000";
      var result = contrastRatio(foreground, background.color);
      return { run: runIndex, foreground: foreground, ratio: result.known ? result.ratio : null };
    });
    if (runContrasts.some(function (entry) { return entry.ratio === null; })) {
      return { contrast: null, reason: "foreground color is unknown", low: false };
    }
    var uniqueRatios = runContrasts.filter(function (entry, index) {
      return runContrasts.findIndex(function (candidate) { return candidate.ratio === entry.ratio; }) === index;
    });
    var minimum = Math.min.apply(Math, runContrasts.map(function (entry) { return entry.ratio; }));
    return {
      contrast: uniqueRatios.length > 1
        ? { ratio: minimum, runs: runContrasts }
        : minimum,
      reason: null,
      low: minimum < 4.5
    };
  }

  function runFontSize(element, run) { return run.fontSize !== undefined ? run.fontSize : (element.fontSize !== undefined ? element.fontSize : 18); }
  function runFontFace(element, run, themeBodyFontFace) { return run.fontFace || element.fontFace || themeBodyFontFace || "Calibri"; }

  function fontHeight(context, fontSize, fontFace, bold, italic) {
    context.font = (bold ? "bold " : "") + (italic ? "italic " : "") + fontCss(fontSize, fontFace);
    var metrics = context.measureText("Hg");
    var ascent = metrics.fontBoundingBoxAscent || metrics.actualBoundingBoxAscent;
    var descent = metrics.fontBoundingBoxDescent || metrics.actualBoundingBoxDescent;
    if (typeof ascent === "number" && typeof descent === "number" && ascent + descent > 0) {
      return { heightPx: ascent + descent, realFontMetrics: true };
    }
    return { heightPx: fontSize * 96 / 72, realFontMetrics: false };
  }

  function measureText(element, context, themeBodyFontFace) {
    var runs = textRuns(element);
    var margins = marginPoints(element.margin);
    var available = Math.max(0, (element.w - (margins[0] + margins[1]) / 72) * 96);
    available = Math.max(0, available - bulletIndentPoints(element) * 96 / 72);
    var lines = [];
    var current = { width: 0, baseHeightPx: 0, allReal: true };
    var paragraphCount = 1;
    var tokens = [];
    runs.forEach(function (run) {
      var pieces = run.text.split(/(\s+)/);
      pieces.forEach(function (piece) {
        if (piece) tokens.push({ text: piece, run: run });
      });
    });
    function pushLine() {
      lines.push(current);
      current = { width: 0, baseHeightPx: 0, allReal: true };
    }
    tokens.forEach(function (token) {
      var parts = token.text.split(/(\r?\n)/);
      parts.forEach(function (part) {
        if (part === "\n" || part === "\r\n") {
          pushLine();
          paragraphCount += 1;
          return;
        }
        if (!part) return;
        var run = token.run;
        var size = runFontSize(element, run);
        var face = runFontFace(element, run, themeBodyFontFace);
        context.font = (run.bold === undefined ? element.bold : run.bold ? true : false) ? "bold " : "";
        context.font += (run.italic === undefined ? Boolean(element.italic) : Boolean(run.italic)) ? "italic " : "";
        context.font += fontCss(size, face);
        var wordWidth = context.measureText(part).width;
        var height = fontHeight(context, size, face, run.bold === undefined ? element.bold : run.bold, run.italic === undefined ? element.italic : run.italic);
        current.baseHeightPx = Math.max(current.baseHeightPx, height.heightPx);
        current.allReal = current.allReal && height.realFontMetrics;
        if (/^\s+$/.test(part)) {
          if (current.width > 0 && current.width + wordWidth <= available) current.width += wordWidth;
          return;
        }
        if (current.width > 0 && current.width + wordWidth > available) pushLine();
        current.baseHeightPx = Math.max(current.baseHeightPx, height.heightPx);
        current.allReal = current.allReal && height.realFontMetrics;
        if (wordWidth > available && available > 0) {
          for (var charIndex = 0; charIndex < part.length; charIndex += 1) {
            var characterWidth = context.measureText(part.charAt(charIndex)).width;
            if (current.width > 0 && current.width + characterWidth > available) pushLine();
            current.width += characterWidth;
            current.baseHeightPx = Math.max(current.baseHeightPx, height.heightPx);
            current.allReal = current.allReal && height.realFontMetrics;
          }
        } else {
          current.width += wordWidth;
        }
      });
    });
    if (current.width > 0 || lines.length === 0 || flattenText(element).endsWith("\n")) pushLine();
    var pxHeight = 0;
    var allRealMetrics = true;
    lines.forEach(function (line) {
      var lineHeightPt = element.lineSpacing !== undefined ? element.lineSpacing : line.baseHeightPx * 72 / 96;
      if (element.lineSpacing === undefined && element.lineSpacingMultiple !== undefined) lineHeightPt *= element.lineSpacingMultiple;
      pxHeight += lineHeightPt * 96 / 72;
      allRealMetrics = allRealMetrics && line.allReal;
    });
    pxHeight += (margins[2] + margins[3]) * 96 / 72;
    pxHeight += paragraphCount * ((element.paraSpaceBefore || 0) + (element.paraSpaceAfter || 0)) * 96 / 72;
    var requestedSizes = [];
    runs.forEach(function (run) { requestedSizes.push(runFontSize(element, run)); });
    return {
      needed_h: pxHeight / 96,
      line_count: lines.length,
      minimum_font_size_pt: Math.min.apply(Math, requestedSizes),
      metrics_source: allRealMetrics ? "canvas-font-metrics" : "canvas-width-with-font-size-fallback"
    };
  }

  function elementFontSizes(element) {
    var sizes = [];
    function add(value) { if (typeof value === "number") sizes.push(value); }
    if (element.type === "table") {
      add(element.fontSize);
      element.rows.forEach(function (row) {
        row.forEach(function (cell) {
          if (cell && typeof cell === "object") {
            if (cell.options) add(cell.options.fontSize);
            if (Array.isArray(cell.text)) cell.text.forEach(function (run) { if (run.options) add(run.options.fontSize); });
          }
        });
      });
    } else if (element.type === "chart" && element.options) {
      Object.keys(element.options).forEach(function (key) { if (key.indexOf("FontSize") !== -1) add(element.options[key]); });
    }
    return sizes;
  }

  function addElement(pptx, slide, element, themeColors, context) {
    if (element.type === "text") {
      var text = element.text !== undefined ? element.text : element.runs.map(function (run) {
        var options = Object.assign({}, run);
        delete options.text;
        return { text: run.text, options: options };
      });
      var textOptions = Object.assign({}, element);
      delete textOptions.type;
      delete textOptions.text;
      delete textOptions.runs;
      slide.addText(text, textOptions);
    } else if (element.type === "shape") {
      var shapeOptions = Object.assign({}, element);
      delete shapeOptions.type;
      delete shapeOptions.shape;
      slide.addShape(element.shape, shapeOptions);
    } else if (element.type === "image") {
      var imageOptions = Object.assign({}, element, { data: element.dataUrl });
      delete imageOptions.type;
      delete imageOptions.dataUrl;
      if (typeof element.sizing === "string") imageOptions.sizing = { type: element.sizing, w: element.w, h: element.h };
      slide.addImage(imageOptions);
    } else if (element.type === "chart") {
      var chartOptions = Object.assign({}, element.options || {}, { x: element.x, y: element.y, w: element.w, h: element.h });
      var chartColors = element.datasets.map(function (dataset) { return dataset.color; });
      var hasDatasetColor = chartColors.some(function (color) { return color !== undefined; });
      var data = element.datasets.map(function (dataset) {
        return { name: dataset.name, labels: element.labels, values: dataset.values };
      });
      if (chartOptions.chartColors === undefined && themeColors && themeColors.length > 0) {
        chartOptions.chartColors = themeColors.slice();
      }
      if (hasDatasetColor) {
        var inheritedColors = chartOptions.chartColors ? chartOptions.chartColors.slice() : null;
        if (!inheritedColors) {
          var probe = new global.PptxGenJS();
          var probeSlide = probe.addSlide();
          probeSlide.addChart(element.chartType, data, { x: 0, y: 0, w: 1, h: 1 });
          inheritedColors = probeSlide._slideObjects[0].options.chartColors.slice();
        }
        var basePalette = inheritedColors.slice();
        for (var paletteIndex = 0; paletteIndex < element.datasets.length; paletteIndex += 1) {
          inheritedColors[paletteIndex] = basePalette[paletteIndex % basePalette.length];
        }
        chartColors.forEach(function (color, colorIndex) {
          if (color !== undefined) inheritedColors[colorIndex] = color;
        });
        chartOptions.chartColors = inheritedColors;
      }
      slide.addChart(element.chartType, data, chartOptions);
    } else if (element.type === "table") {
      var tableOptions = Object.assign({}, element);
      delete tableOptions.type;
      delete tableOptions.rows;
      slide.addTable(element.rows, tableOptions);
    }
  }

  function xmlChildren(node) {
    if (node.children) return Array.prototype.slice.call(node.children);
    if (node.childNodes) return Array.prototype.slice.call(node.childNodes).filter(function (child) { return child.nodeType === 1; });
    return [];
  }

  function descendantNodes(node) {
    var found = [];
    xmlChildren(node).forEach(function (child) {
      found.push(child);
      found = found.concat(descendantNodes(child));
    });
    return found;
  }

  function getBoxFromXmlNode(node) {
    var descendants = descendantNodes(node);
    var transform = descendants.find(function (entry) { return entry.localName === "xfrm"; });
    if (!transform) return null;
    var transformChildren = descendantNodes(transform);
    var offset = transformChildren.find(function (entry) { return entry.localName === "off"; });
    var extent = transformChildren.find(function (entry) { return entry.localName === "ext"; });
    if (!offset || !extent) return null;
    return {
      x: Number(offset.getAttribute("x")) / EMU_PER_INCH,
      y: Number(offset.getAttribute("y")) / EMU_PER_INCH,
      w: Number(extent.getAttribute("cx")) / EMU_PER_INCH,
      h: Number(extent.getAttribute("cy")) / EMU_PER_INCH
    };
  }

  function presentationObjectsFromXml(xml) {
    var root = new DOMParser().parseFromString(xml, "application/xml");
    return descendantNodes(root).filter(function (node) {
      return ["sp", "graphicFrame", "pic", "cxnSp"].indexOf(node.localName) !== -1;
    }).map(function (node) { return { node: node, box: getBoxFromXmlNode(node) }; }).filter(function (entry) { return entry.box !== null; });
  }

  function objectTypeFromXml(node, requestedType) {
    if (requestedType !== "shape") return requestedType;
    return node.localName === "cxnSp" ? "shape" : "shape";
  }

  async function createMeasurementReport(args, pptxBytes, textMeasurements) {
    var archive = await global.JSZip.loadAsync(pptxBytes);
    var report = { layout: { name: "LAYOUT_WIDE", width_in: SLIDE_WIDTH, height_in: SLIDE_HEIGHT }, slides: [] };
    var metricsSources = [];
    args.slides.forEach(function (source, slideIndex) {
      var slideNumber = slideIndex + 1;
      var xmlPromise = archive.file("ppt/slides/slide" + slideNumber + ".xml").async("string");
      report.slides.push(xmlPromise.then(function (xml) {
        var generated = presentationObjectsFromXml(xml);
        var boxes = source.elements.map(function (element, elementIndex) {
          var geometry = generated[elementIndex];
          return geometry ? geometry.box : { x: element.x, y: element.y, w: element.w, h: element.h };
        });
        var overlaps = [];
        var overlappingElements = Object.create(null);
        for (var firstIndex = 0; firstIndex < boxes.length; firstIndex += 1) {
          for (var secondIndex = firstIndex + 1; secondIndex < boxes.length; secondIndex += 1) {
            var first = boxes[firstIndex];
            var second = boxes[secondIndex];
            var overlapWidth = Math.min(first.x + first.w, second.x + second.w) - Math.max(first.x, second.x);
            var overlapHeight = Math.min(first.y + first.h, second.y + second.h) - Math.max(first.y, second.y);
            if (overlapWidth > 0 && overlapHeight > 0) {
              overlaps.push({ elements: [firstIndex, secondIndex], area_in2: overlapWidth * overlapHeight });
              overlappingElements[firstIndex] = true;
              overlappingElements[secondIndex] = true;
            }
          }
        }
        var elements = [];
        var outsideBounds = [];
        var minimumFontSize = null;
        source.elements.forEach(function (element, elementIndex) {
          var geometry = generated[elementIndex];
          var box = boxes[elementIndex];
          var outside = box.x < 0 || box.y < 0 || box.x + box.w > SLIDE_WIDTH || box.y + box.h > SLIDE_HEIGHT;
          var entry = {
            index: elementIndex,
            type: objectTypeFromXml(geometry ? geometry.node : { localName: "sp" }, element.type)
          };
          var hasFinding = outside || Boolean(overlappingElements[elementIndex]);
          if (outside) outsideBounds.push({ index: elementIndex, type: element.type, box: box });
          if (element.type === "text") {
            var measurement = textMeasurements[slideIndex][elementIndex];
            metricsSources.push(measurement.metrics_source);
            var overflow = measurement.needed_h > box.h;
            var background = effectiveTextBackground(source, args, element, elementIndex);
            var contrast = textContrast(element, background);
            entry.needed_h = measurement.needed_h;
            entry.line_count = measurement.line_count;
            if (contrast.contrast !== null) entry.contrast = contrast.contrast;
            else {
              entry.contrast = null;
              entry.contrast_reason = contrast.reason;
            }
            hasFinding = hasFinding || overflow || contrast.low || contrast.contrast === null;
            if (overflow) entry.overflow = true;
            var currentMin = measurement.minimum_font_size_pt;
            minimumFontSize = minimumFontSize === null ? currentMin : Math.min(minimumFontSize, currentMin);
          } else {
            var explicitFontSizes = elementFontSizes(element);
            if (explicitFontSizes.length > 0) {
              entry.minimum_font_size_pt = Math.min.apply(Math, explicitFontSizes);
              minimumFontSize = minimumFontSize === null
                ? entry.minimum_font_size_pt
                : Math.min(minimumFontSize, entry.minimum_font_size_pt);
            }
          }
          if (element.type === "image" && element.dataUrl.slice(0, 30).toLowerCase().indexOf("data:image/svg+xml") === 0) {
            if (!report.warnings) report.warnings = [];
            report.warnings.push({ slide: slideNumber, element: elementIndex, type: "image", field: "dataUrl", fact: "SVG image data is not supported by the presentation viewer or thumbnail renderer." });
          }
          if (hasFinding) entry.box = box;
          elements.push(entry);
        });
        var slideReport = {
          slide: slideNumber,
          elements: elements,
          minimum_font_size_pt: minimumFontSize
        };
        if (overlaps.length) slideReport.overlaps = overlaps;
        if (outsideBounds.length) slideReport.outside_bounds = outsideBounds;
        return slideReport;
      }));
    });
    report.slides = await Promise.all(report.slides);
    var distinctMetricsSources = metricsSources.filter(function (source, index) { return metricsSources.indexOf(source) === index; });
    if (distinctMetricsSources.length) report.metrics_source = distinctMetricsSources.length === 1 ? distinctMetricsSources[0] : "mixed";
    return report;
  }

  async function createPresentation(argumentsObject, host) {
    if (!argumentsObject || !host || typeof host.artifactDeliverBytes !== "function") {
      throw new Error("The presentation host capability is unavailable.");
    }
    await loadRuntime();
    var pptx = new global.PptxGenJS();
    pptx.layout = "LAYOUT_WIDE";
    var validation = validateSlidesForGeneration(argumentsObject, pptx);
    await validation;

    pptx.title = argumentsObject.title;
    pptx.subject = argumentsObject.title;
    if (argumentsObject.theme && argumentsObject.theme.fonts) {
      pptx.theme = Object.assign({}, argumentsObject.theme.fonts);
    }
    var contextCanvas = document.createElement("canvas");
    var measurementContext = contextCanvas.getContext("2d");
    if (!measurementContext) throw new Error("A 2D canvas is unavailable for text measurement.");
    var textMeasurements = [];
    var themeColors = argumentsObject.theme && argumentsObject.theme.colors
      ? Object.keys(argumentsObject.theme.colors).map(function (key) { return argumentsObject.theme.colors[key]; })
      : null;
    var themeBodyFontFace = argumentsObject.theme && argumentsObject.theme.fonts && argumentsObject.theme.fonts.bodyFontFace;
    argumentsObject.slides.forEach(function (source, slideIndex) {
      var slide = pptx.addSlide();
      var bg = source.background !== undefined ? source.background : (argumentsObject.theme && argumentsObject.theme.background);
      if (bg !== undefined) slide.background = typeof bg === "string" ? { color: bg } : bg;
      if (source.notes !== undefined) slide.addNotes(Array.isArray(source.notes) ? source.notes : [source.notes]);
      textMeasurements.push([]);
      source.elements.forEach(function (element, elementIndex) {
        if (element.type === "text") textMeasurements[slideIndex][elementIndex] = measureText(element, measurementContext, themeBodyFontFace);
        try {
          addElement(pptx, slide, element, themeColors, elementContext(slideIndex + 1, elementIndex, element.type));
        } catch (error) {
          if (error && error.message && error.message.indexOf("Slide ") === 0) throw error;
          throw new Error(elementContext(slideIndex + 1, elementIndex, element.type) + ": PptxGenJS rejected the element: " + (error && error.message ? error.message : String(error)));
        }
      });
    });

    var pptxBase64 = await pptx.write({ outputType: "base64" });
    var pptxBytes = bytesFromBase64(pptxBase64);
    var measurementReport = await createMeasurementReport(argumentsObject, pptxBytes, textMeasurements);
    var thumbnailCanvas = await renderFirstSlideThumbnail(pptxBytes);
    var thumbnailBase64 = thumbnailCanvas.toDataURL("image/png").split(",")[1];
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
    var content = {
      message: "PowerPoint artifact stored in this conversation.",
      artifactId: delivered.id,
      layout: measurementReport.layout,
      measurementReport: measurementReport
    };
    return {
      success: true,
      content: JSON.stringify(content),
      artifactId: delivered.id,
      artifactType: "application/vnd.openxmlformats-officedocument.presentationml.presentation",
      measurementReport: measurementReport
    };
  }

  global.presentationChartOptionProperties = Object.freeze(CHART_PROPERTIES.slice());
  global.createPresentation = createPresentation;
}(window));
