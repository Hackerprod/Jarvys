(function () {
  "use strict";

  var deck = null;
  var currentSlide = 0;
  var canvas = document.getElementById("slide");
  var counter = document.getElementById("counter");
  var previous = document.getElementById("previous");
  var next = document.getElementById("next");
  var status = document.getElementById("status");

  function updateControls() {
    counter.textContent = "Diapositiva " + (currentSlide + 1) + " de " + deck.slidePaths.length;
    previous.disabled = currentSlide === 0;
    next.disabled = currentSlide === deck.slidePaths.length - 1;
  }

  async function showSlide(index) {
    currentSlide = index;
    await PptxDeckRenderer.renderSlide(deck, currentSlide, canvas);
    updateControls();
  }

  async function loadPresentation() {
    var artifact = window.JarvysArtifact;
    if (!artifact || !artifact.url) throw new Error("The presentation artifact is unavailable.");
    var response = await fetch(artifact.url, { method: "GET", credentials: "omit", cache: "no-store" });
    if (!response.ok) throw new Error("The presentation artifact could not be read.");
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

  loadPresentation().catch(showFailure);
}());
