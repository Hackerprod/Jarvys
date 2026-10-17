(function () {
  "use strict";

  var isSpanish = (navigator.language || "en").toLowerCase().indexOf("es") === 0;
  var labels = isSpanish ? {
    title: "Presentación",
    navigation: "Controles de diapositivas",
    previous: "Anterior",
    next: "Siguiente",
    loading: "Cargando…",
    slide: function (index, total) { return "Diapositiva " + index + " de " + total; },
    imageAlt: function (index) { return "Diapositiva " + index; },
    loadFailure: "No se pudo abrir la presentación. Vuelve a intentarlo o abre el archivo en una aplicación compatible.",
    slideFailure: function (index, total) {
      return "No se pudo mostrar la diapositiva " + index + " de " + total + ". Prueba otra diapositiva o abre el archivo en una aplicación compatible.";
    }
  } : {
    title: "Presentation",
    navigation: "Slide controls",
    previous: "Previous",
    next: "Next",
    loading: "Loading…",
    slide: function (index, total) { return "Slide " + index + " of " + total; },
    imageAlt: function (index) { return "Slide " + index; },
    loadFailure: "The presentation could not be opened. Try again or open the file in a compatible app.",
    slideFailure: function (index, total) {
      return "Slide " + index + " of " + total + " could not be displayed. Try another slide or open the file in a compatible app.";
    }
  };

  var stage = document.getElementById("stage");
  var screenTitle = document.getElementById("screen-title");
  var navigation = document.getElementById("navigation");
  var counter = document.getElementById("counter");
  var previous = document.getElementById("previous");
  var next = document.getElementById("next");
  var status = document.getElementById("status");
  var slideImage = document.getElementById("slide");
  var slides = [];
  var currentSlide = 0;
  var renderRequest = 0;

  document.documentElement.lang = isSpanish ? "es" : "en";
  document.title = labels.title;
  screenTitle.textContent = labels.title;
  navigation.setAttribute("aria-label", labels.navigation);
  previous.textContent = labels.previous;
  next.textContent = labels.next;
  counter.textContent = labels.loading;

  function updateControls() {
    if (slides.length === 0) {
      counter.textContent = labels.loading;
      previous.disabled = true;
      next.disabled = true;
      return;
    }
    counter.textContent = labels.slide(currentSlide + 1, slides.length);
    previous.disabled = currentSlide === 0;
    next.disabled = currentSlide === slides.length - 1;
  }

  function fitSlide() {
    if (!slideImage || !slideImage.naturalWidth || !slideImage.naturalHeight) return;
    var style = getComputedStyle(stage);
    var availableWidth = stage.clientWidth - parseFloat(style.paddingLeft || 0) - parseFloat(style.paddingRight || 0);
    var availableHeight = stage.clientHeight - parseFloat(style.paddingTop || 0) - parseFloat(style.paddingBottom || 0);
    if (availableWidth <= 0 || availableHeight <= 0) return;
    var scale = Math.min(availableWidth / slideImage.naturalWidth, availableHeight / slideImage.naturalHeight);
    slideImage.style.width = slideImage.naturalWidth * scale + "px";
    slideImage.style.height = slideImage.naturalHeight * scale + "px";
  }

  function decodeSlide(svg, index) {
    return new Promise(function (resolve, reject) {
      var image = document.createElement("img");
      image.id = "slide";
      image.className = "slide-image";
      image.alt = labels.imageAlt(index + 1);
      image.onload = function () {
        if (image.naturalWidth > 0 && image.naturalHeight > 0) resolve(image);
        else reject(new Error("slide-image-empty"));
      };
      image.onerror = function () { reject(new Error("slide-image-decode-failed")); };
      image.src = "data:image/svg+xml;charset=utf-8," + encodeURIComponent(svg);
      if (image.complete && image.naturalWidth > 0 && image.naturalHeight > 0) resolve(image);
    });
  }

  async function showSlide(index) {
    if (!Number.isInteger(index) || index < 0 || index >= slides.length) return;
    currentSlide = index;
    var request = ++renderRequest;
    updateControls();
    status.hidden = true;
    stage.replaceChildren();
    slideImage = null;

    try {
      var result = slides[index];
      if (!result || typeof result.svg !== "string" || result.svg.trim().length === 0) {
        throw new Error("slide-svg-missing");
      }
      var image = await decodeSlide(result.svg, index);
      if (request !== renderRequest) return;
      slideImage = image;
      stage.replaceChildren(image);
      fitSlide();
    } catch (_) {
      if (request !== renderRequest) return;
      stage.replaceChildren();
      slideImage = null;
      status.textContent = labels.slideFailure(index + 1, slides.length);
      status.hidden = false;
    }
  }

  function showLoadFailure() {
    renderRequest += 1;
    slides = [];
    stage.replaceChildren();
    slideImage = null;
    previous.disabled = true;
    next.disabled = true;
    counter.textContent = labels.loading;
    status.textContent = labels.loadFailure;
    status.hidden = false;
  }

  async function loadPresentation() {
    var artifact = window.JarvysArtifact;
    if (!artifact || typeof artifact.url !== "string" || artifact.url.length === 0) {
      throw new Error("presentation-artifact-unavailable");
    }

    var glimpse = await import("../vendor/pptx-glimpse.bundle.js");
    var response = await fetch(artifact.url, { method: "GET", credentials: "omit", cache: "no-store" });
    if (!response.ok) throw new Error("presentation-artifact-unavailable");
    var bytes = new Uint8Array(await response.arrayBuffer());
    if (bytes.byteLength === 0 || typeof glimpse.convertPptxToSvg !== "function") {
      throw new Error("presentation-renderer-unavailable");
    }

    var report = await glimpse.convertPptxToSvg(bytes, { textOutput: "text", width: 1280 });
    if (!report || !Array.isArray(report.slides) || report.slides.length === 0) {
      throw new Error("presentation-render-empty");
    }
    slides = report.slides;
    currentSlide = 0;
    updateControls();
    await showSlide(0);
  }

  previous.addEventListener("click", function () {
    if (currentSlide > 0) showSlide(currentSlide - 1);
  });
  next.addEventListener("click", function () {
    if (currentSlide < slides.length - 1) showSlide(currentSlide + 1);
  });
  window.addEventListener("resize", fitSlide);

  window.PptxPresentationViewer = {
    loadPresentation: loadPresentation,
    showSlide: showSlide
  };
  window.PptxPresentationViewerReady = loadPresentation().catch(showLoadFailure);
}());
