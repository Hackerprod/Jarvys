(function () {
  "use strict";

  function showError(message) {
    var status = document.createElement("p");
    status.className = "status";
    status.setAttribute("role", "status");
    status.textContent = message;
    document.querySelector("main").appendChild(status);
  }

  async function render() {
    var artifact = window.JarvysArtifact;
    if (!artifact || !artifact.url) throw new Error("The chart artifact is unavailable.");
    var response = await fetch(artifact.url, { method: "GET", credentials: "omit", cache: "no-store" });
    if (!response.ok) throw new Error("The chart artifact could not be read.");
    var chartArtifact = await response.json();
    document.getElementById("title").textContent = chartArtifact.title || artifact.title || "Gráfica";
    var options = chartArtifact.options || {};
    var suppliedPlugins = options.plugins || {};
    var plugins = Object.assign({}, suppliedPlugins, {
      title: Object.assign({}, suppliedPlugins.title || {}, {
        display: true,
        text: chartArtifact.title || artifact.title || "Gráfica"
      })
    });
    new Chart(document.getElementById("chart").getContext("2d"), {
      type: chartArtifact.chartType,
      data: chartArtifact.data,
      options: Object.assign({}, options, { responsive: true, maintainAspectRatio: false, plugins: plugins })
    });
  }

  render().catch(function (failure) {
    showError(failure && failure.message ? failure.message : "No se pudo mostrar la gráfica.");
  });
}());
