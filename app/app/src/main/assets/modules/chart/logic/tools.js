(function (global) {
  "use strict";

  // The host decodes cards to 640x360 and allows the decoder's existing 2x density margin.
  var THUMBNAIL_WIDTH = 1280;
  var THUMBNAIL_HEIGHT = 720;
  var MAX_THUMBNAIL_BASE64_CHARS = Math.ceil(THUMBNAIL_WIDTH * THUMBNAIL_HEIGHT * 4 / 3) * 4;
  var CHART_TYPES = ["bar", "bubble", "doughnut", "line", "pie", "polarArea", "radar", "scatter"];

  class ChartInputError extends Error {
    constructor(message) {
      super(message);
      this.name = "ChartInputError";
    }
  }

  function loadAssetScript(path) {
    return new Promise(function (resolve, reject) {
      var script = document.createElement("script");
      script.src = path;
      script.onload = resolve;
      script.onerror = function () { reject(new Error("A local chart asset could not be loaded.")); };
      document.head.appendChild(script);
    });
  }

  function base64FromBytes(value) {
    var bytes = value instanceof Uint8Array ? value : new Uint8Array(value);
    var binary = "";
    for (var index = 0; index < bytes.length; index += 1) binary += String.fromCharCode(bytes[index]);
    return btoa(binary);
  }

  function parseDeliveryResult(raw) {
    if (raw && typeof raw === "object") return raw;
    if (typeof raw === "string") return JSON.parse(raw);
    throw new Error("The host did not return an artifact reference.");
  }

  async function createChartInternal(argumentsObject, host) {
    if (!host || typeof host.artifactDeliverBytes !== "function") {
      throw new Error("The chart host capability is unavailable.");
    }
    if (!argumentsObject || typeof argumentsObject !== "object" || Array.isArray(argumentsObject)) {
      throw new ChartInputError("Chart: arguments must be an object containing title, chartType, labels, and datasets.");
    }
    if (typeof argumentsObject.chartType !== "string" || CHART_TYPES.indexOf(argumentsObject.chartType) === -1) {
      throw new ChartInputError("Chart: chartType is not supported by Chart.js; choose bar, bubble, doughnut, line, pie, polarArea, radar, or scatter.");
    }
    if (!global.Chart) await loadAssetScript("vendor/chart.umd.min.js");

    var canvas = document.createElement("canvas");
    canvas.width = THUMBNAIL_WIDTH;
    canvas.height = THUMBNAIL_HEIGHT;
    canvas.setAttribute("aria-label", argumentsObject.title);
    document.body.appendChild(canvas);

    var suppliedOptions = argumentsObject.options || {};
    var suppliedPlugins = suppliedOptions.plugins || {};
    var suppliedTitle = suppliedPlugins.title || {};
    var options = Object.assign({}, suppliedOptions, {
      responsive: false,
      devicePixelRatio: 1,
      animation: false,
      maintainAspectRatio: false,
      plugins: Object.assign({}, suppliedPlugins, {
        title: Object.assign({}, suppliedTitle, { display: true, text: argumentsObject.title })
      })
    });
    var chart = new global.Chart(canvas.getContext("2d"), {
      type: argumentsObject.chartType,
      data: { labels: argumentsObject.labels, datasets: argumentsObject.datasets },
      options: options
    });

    chart.update("none");
    chart.draw();
    var thumbnailDataUrl = canvas.toDataURL("image/png");
    var thumbnailBase64 = thumbnailDataUrl.substring(thumbnailDataUrl.indexOf(",") + 1);
    if (thumbnailBase64.length > MAX_THUMBNAIL_BASE64_CHARS) {
      throw new ChartInputError("The chart thumbnail exceeds the displayed-card budget. Reduce the chart's data or visual complexity and retry.");
    }
    var artifact = {
      schemaVersion: 1,
      title: argumentsObject.title,
      chartType: argumentsObject.chartType,
      data: { labels: argumentsObject.labels, datasets: argumentsObject.datasets },
      options: suppliedOptions
    };
    var bytes = new TextEncoder().encode(JSON.stringify(artifact));
    var delivered = parseDeliveryResult(host.artifactDeliverBytes(
      base64FromBytes(bytes),
      "chart.json",
      "application/vnd.jarvys.chart+json",
      argumentsObject.title,
      thumbnailBase64,
      "image/png",
      host.newArtifactDeliveryId()
    ));
    if (!delivered.id) throw new Error("The host did not confirm the chart artifact id.");
    return {
      success: true,
      content: "Chart artifact stored in this conversation. Artifact id: " + delivered.id,
      artifactId: delivered.id,
      artifactType: "application/vnd.jarvys.chart+json"
    };
  }

  async function createChart(argumentsObject, host) {
    try {
      return await createChartInternal(argumentsObject, host);
    } catch (error) {
      if (error instanceof ChartInputError) return { success: false, content: error.message };
      throw error;
    }
  }

  global.createChart = createChart;
}(window));
