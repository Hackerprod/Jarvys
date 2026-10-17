(function () {
  "use strict";

  var deck = null;
  var currentSlide = 0;
  var stage = document.getElementById("stage");
  var canvas = document.getElementById("slide");
  var counter = document.getElementById("counter");
  var previous = document.getElementById("previous");
  var next = document.getElementById("next");
  var warning = document.getElementById("warning");
  var status = document.getElementById("status");

  function updateControls() {
    counter.textContent = "Diapositiva " + (currentSlide + 1) + " de " + deck.slidePaths.length;
    previous.disabled = currentSlide === 0;
    next.disabled = currentSlide === deck.slidePaths.length - 1;
  }

  async function showSlide(index) {
    await PptxDeckRenderer.renderSlide(deck, index, canvas);
    currentSlide = index;
    status.hidden = true;
    warning.hidden = !canvas.presentationWarnings || canvas.presentationWarnings.length === 0;
    warning.textContent = (canvas.presentationWarnings || []).join(" ");
    // The warning/status and navigation occupy their own grid rows. Measure the
    // stage after their visibility has settled so the slide never runs underneath them.
    fitCanvas();
    updateControls();
  }

  function fitCanvas() {
    if (!deck) return;
    var style = getComputedStyle(stage);
    var availableWidth = stage.clientWidth - parseFloat(style.paddingLeft || 0) - parseFloat(style.paddingRight || 0);
    var availableHeight = stage.clientHeight - parseFloat(style.paddingTop || 0) - parseFloat(style.paddingBottom || 0);
    var fit = PptxDeckRenderer.fitSlideToBounds(availableWidth, availableHeight, deck.width, deck.height);
    if (fit.width <= 0 || fit.height <= 0) return;
    canvas.style.width = fit.width + "px";
    canvas.style.height = fit.height + "px";
  }

  async function loadPresentation() {
    var artifact = window.JarvysArtifact;
    if (!artifact || !artifact.url) throw new Error("No se recibió el archivo de presentación.");
    var response = await fetch(artifact.url, { method: "GET", credentials: "omit", cache: "no-store" });
    if (!response.ok) throw new Error("No se pudo leer el archivo de presentación.");
    deck = await PptxDeckRenderer.loadDeck(await response.arrayBuffer());
    await showSlide(0);
  }

  previous.addEventListener("click", function () {
    if (currentSlide > 0) showSlide(currentSlide - 1).catch(showFailure);
  });
  next.addEventListener("click", function () {
    if (deck && currentSlide < deck.slidePaths.length - 1) showSlide(currentSlide + 1).catch(showFailure);
  });

  function showFailure(failure) {
    status.hidden = false;
    status.textContent = failure && failure.message ? failure.message : "No se pudo mostrar la presentación.";
  }

  window.addEventListener("resize", fitCanvas);
  window.PptxPresentationViewerReady = loadPresentation().catch(showFailure);
}());
