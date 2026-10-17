(function () {
  "use strict";

  var isSpanish = (navigator.language || "en").toLowerCase().indexOf("es") === 0;
  var labels = isSpanish ? {
    title: "Presentación",
    stage: "Diapositiva de la presentación",
    navigation: "Controles de diapositivas",
    previous: "Anterior",
    next: "Siguiente",
    loading: "Cargando…",
    loadingPresentation: "Cargando presentación…",
    slide: function (index, total) { return "Diapositiva " + index + " de " + total; },
    imageAlt: function (index) { return "Diapositiva " + index; },
    loadFailure: "No se pudo abrir la presentación. Vuelve a intentarlo o abre el archivo en una aplicación compatible.",
    slideFailure: function (index, total) {
      return "No se pudo mostrar la diapositiva " + index + " de " + total + ". Prueba otra diapositiva o abre el archivo en una aplicación compatible.";
    }
  } : {
    title: "Presentation",
    stage: "Presentation slide",
    navigation: "Slide controls",
    previous: "Previous",
    next: "Next",
    loading: "Loading…",
    loadingPresentation: "Loading presentation…",
    slide: function (index, total) { return "Slide " + index + " of " + total; },
    imageAlt: function (index) { return "Slide " + index; },
    loadFailure: "The presentation could not be opened. Try again or open the file in a compatible app.",
    slideFailure: function (index, total) {
      return "Slide " + index + " of " + total + " could not be displayed. Try another slide or open the file in a compatible app.";
    }
  };

  var stage = document.getElementById("stage");
  var loading = document.getElementById("loading");
  var navigation = document.getElementById("navigation");
  var counter = document.getElementById("counter");
  var previous = document.getElementById("previous");
  var next = document.getElementById("next");
  var status = document.getElementById("status");
  var slideImage = document.getElementById("slide");
  var slideCount = 0;
  var pptxBytes = null;
  var glimpse = null;
  var slideCache = new Map();
  var slideRenderPromises = new Map();
  var currentSlide = 0;
  var renderRequest = 0;

  function markViewerReady() {
    if (typeof window.__presentationViewerReady === "function") {
      window.__presentationViewerReady();
    }
  }

  document.documentElement.lang = isSpanish ? "es" : "en";
  document.title = labels.title;
  stage.setAttribute("aria-label", labels.stage);
  loading.textContent = labels.loadingPresentation;
  navigation.setAttribute("aria-label", labels.navigation);
  previous.textContent = labels.previous;
  next.textContent = labels.next;
  counter.textContent = labels.loading;

  function updateControls() {
    if (slideCount === 0) {
      counter.textContent = labels.loading;
      previous.disabled = true;
      next.disabled = true;
      return;
    }
    counter.textContent = labels.slide(currentSlide + 1, slideCount);
    previous.disabled = currentSlide === 0;
    next.disabled = currentSlide === slideCount - 1;
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

  async function countPresentationSlides(bytes) {
    if (!window.JSZip || typeof window.JSZip.loadAsync !== "function") {
      throw new Error("presentation-archive-reader-unavailable");
    }
    var archive = await window.JSZip.loadAsync(bytes);
    var presentation = archive.file("ppt/presentation.xml");
    if (!presentation) throw new Error("presentation-slide-list-unavailable");
    var xml = await presentation.async("string");
    var documentXml = new DOMParser().parseFromString(xml, "application/xml");
    if (documentXml.getElementsByTagName("parsererror").length > 0) {
      throw new Error("presentation-slide-list-invalid");
    }
    var count = documentXml.getElementsByTagNameNS(
      "http://schemas.openxmlformats.org/presentationml/2006/main",
      "sldId"
    ).length;
    if (count === 0) throw new Error("presentation-has-no-slides");
    return count;
  }

  function convertSlide(index) {
    if (slideCache.has(index)) return Promise.resolve(slideCache.get(index));
    if (slideRenderPromises.has(index)) return slideRenderPromises.get(index);

    var pending = glimpse.convertPptxToSvg(pptxBytes, {
      textOutput: "text",
      width: 1280,
      slides: [index + 1]
    }).then(function (report) {
      var result = report && Array.isArray(report.slides) && report.slides[0];
      if (!result || result.slideNumber !== index + 1 || typeof result.svg !== "string" || result.svg.trim().length === 0) {
        throw new Error("slide-svg-missing");
      }
      slideCache.set(index, result.svg);
      return result.svg;
    }).finally(function () {
      slideRenderPromises.delete(index);
    });
    slideRenderPromises.set(index, pending);
    return pending;
  }

  async function showSlide(index) {
    if (!Number.isInteger(index) || index < 0 || index >= slideCount) return;
    currentSlide = index;
    var request = ++renderRequest;
    updateControls();
    status.hidden = true;
    loading.hidden = false;
    stage.replaceChildren(loading);
    slideImage = null;

    try {
      var svg = await convertSlide(index);
      var image = await decodeSlide(svg, index);
      if (request !== renderRequest) return;
      slideImage = image;
      loading.hidden = true;
      stage.replaceChildren(image);
      fitSlide();
    } catch (_) {
      if (request !== renderRequest) return;
      loading.hidden = true;
      stage.replaceChildren();
      slideImage = null;
      status.textContent = labels.slideFailure(index + 1, slideCount);
      status.hidden = false;
    }
  }

  function showLoadFailure() {
    renderRequest += 1;
    slideCount = 0;
    stage.replaceChildren();
    slideImage = null;
    previous.disabled = true;
    next.disabled = true;
    counter.textContent = "";
    status.textContent = labels.loadFailure;
    status.hidden = false;
    markViewerReady();
  }

  async function loadPresentation() {
    var artifact = window.JarvysArtifact;
    if (!artifact || typeof artifact.url !== "string" || artifact.url.length === 0) {
      throw new Error("presentation-artifact-unavailable");
    }

    glimpse = await import("../vendor/pptx-glimpse.bundle.js");
    var response = await fetch(artifact.url, { method: "GET", credentials: "omit", cache: "no-store" });
    if (!response.ok) throw new Error("presentation-artifact-unavailable");
    var bytes = new Uint8Array(await response.arrayBuffer());
    if (bytes.byteLength === 0 || typeof glimpse.convertPptxToSvg !== "function") {
      throw new Error("presentation-renderer-unavailable");
    }
    pptxBytes = bytes;
    slideCount = await countPresentationSlides(bytes);
    currentSlide = 0;
    updateControls();
    await showSlide(0);
    markViewerReady();
  }

  previous.addEventListener("click", function () {
    if (currentSlide > 0) showSlide(currentSlide - 1);
  });
  next.addEventListener("click", function () {
    if (currentSlide < slideCount - 1) showSlide(currentSlide + 1);
  });
  window.addEventListener("resize", fitSlide);

  window.PptxPresentationViewer = {
    loadPresentation: loadPresentation,
    showSlide: showSlide
  };
  window.PptxPresentationViewerReady = loadPresentation().catch(showLoadFailure);
}());
