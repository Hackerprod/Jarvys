(function (root, factory) {
  var api = factory(root);
  if (typeof module === "object" && module.exports) module.exports = api;
  root.ChatCanvas = api;
}(this, function (root) {
  "use strict";

  function createModel() { return { bySeq: Object.create(null), seqs: [], patches: 0, fullRenders: 0 }; }
  function applyDeltas(model, deltas, order) {
    for (var i = 0; i < deltas.length; i++) {
      var event = deltas[i];
      if (!event || !isFinite(event.seq)) continue;
      var key = String(event.seq);
      if (!Object.prototype.hasOwnProperty.call(model.bySeq, key)) model.seqs.push(event.seq);
      model.bySeq[key] = event;
      model.patches++;
    }
    if (order) {
      model.seqs = order.slice();
      var retained = Object.create(null);
      for (var kept = 0; kept < model.seqs.length; kept++) retained[String(model.seqs[kept])] = true;
      Object.keys(model.bySeq).forEach(function (key) { if (!retained[key]) delete model.bySeq[key]; });
    }
    return model;
  }
  function safeMarkdown(value) {
    var text = String(value == null ? "" : value).replace(/\r\n?/g, "\n");
    var blocks = [];
    text = text.replace(/```([^\n`]*)\n([\s\S]*?)```/g, function (_, language, code) {
      blocks.push('<pre><code>' + escapeHtml(code) + '</code></pre>');
      return "\u0000" + (blocks.length - 1) + "\u0000";
    });
    text = escapeHtml(text)
      .replace(/`([^`\n]+)`/g, "<code>$1</code>")
      .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
      .replace(/(^|[^*])\*([^*\n]+)\*/g, "$1<em>$2</em>")
      .split(/\n{2,}/).map(function (part) { return "<p>" + part.replace(/\n/g, "<br>") + "</p>"; }).join("");
    return text.replace(/\u0000(\d+)\u0000/g, function (_, index) { return blocks[Number(index)]; });
  }
  function escapeHtml(value) {
    return String(value).replace(/&/g, "&amp;").replace(/</g, "&lt;")
      .replace(/>/g, "&gt;").replace(/\"/g, "&quot;").replace(/'/g, "&#39;");
  }
  function estimatedHeight(event, width) {
    var text = String(event.text || "") + String(event.detail || "");
    var charsPerLine = Math.max(24, Math.floor(width / 8));
    var lines = text.split("\n").reduce(function (n, line) { return n + Math.max(1, Math.ceil(line.length / charsPerLine)); }, 0);
    return Math.max(68, lines * 24 + 44 + (event.imageUrls && event.imageUrls.length ? 180 : 0));
  }

  var model = createModel();
  var heights = Object.create(null);
  var prefix = [0];
  var booted = false;
  var firstPaintMs = null;
  var frameTimes = [];
  var lastFrame = 0;
  var samplingFrames = false;
  var rootElement, itemsElement, topSpacer, bottomSpacer, jumpButton, historyStatus;
  var nodesBySeq = Object.create(null);
  var scrollScheduled = false;
  var lastViewportAnchor = null;
  var pendingBenchmark = false;

  function bootstrap(payload) {
    var page = Array.isArray(payload) ? null : payload || {};
    var events = Array.isArray(payload) ? payload : page.events || [];
    var order = page && Array.isArray(page.order) ? page.order : events.map(function (event) { return event.seq; });
    if (typeof document === "undefined") { applyDeltas(model, events, order); return; }
    var started = performance.now();
    rootElement = document.getElementById("timeline");
    itemsElement = document.getElementById("items");
    topSpacer = document.getElementById("top-spacer");
    bottomSpacer = document.getElementById("bottom-spacer");
    jumpButton = document.getElementById("jump-to-end");
    historyStatus = document.getElementById("history-status");
    if (jumpButton) jumpButton.addEventListener("click", function () {
      rootElement.scrollTo({ top: rootElement.scrollHeight, behavior: "smooth" });
    });
    applyDeltas(model, events, order);
    setHistoryLoading(page && page.loadingOlder);
    model.fullRenders++;
    rebuildPrefix();
    paintWindow();
    rootElement.scrollTop = rootElement.scrollHeight;
    rootElement.addEventListener("scroll", schedulePaint, { passive: true });
    rootElement.addEventListener("scroll", updateJumpButton, { passive: true });
    requestAnimationFrame(function () {
      firstPaintMs = performance.now() - started;
      if (root.Android) root.Android.benchmarkFirstPaint();
      if (pendingBenchmark) runBenchmark();
    });
    booted = true;
  }
  function rebuildPrefix() {
    prefix = [0];
    var width = rootElement ? rootElement.clientWidth : 360;
    for (var i = 0; i < model.seqs.length; i++) {
      var key = String(model.seqs[i]);
      var height = heights[key] || estimatedHeight(model.bySeq[key], width);
      prefix.push(prefix[prefix.length - 1] + height + 12);
    }
  }
  function lowerBound(value) {
    var low = 0, high = model.seqs.length;
    while (low < high) { var mid = (low + high) >> 1; if (prefix[mid + 1] < value) low = mid + 1; else high = mid; }
    return low;
  }
  function visibleAnchor() {
    if (!model.seqs.length) return null;
    var index = lowerBound(rootElement.scrollTop);
    return { seq: model.seqs[index], offset: rootElement.scrollTop - prefix[index] };
  }
  function schedulePaint() {
    if (scrollScheduled) return;
    scrollScheduled = true;
    requestAnimationFrame(function () { scrollScheduled = false; paintWindow(); });
  }
  function updateJumpButton() {
    if (!rootElement || !jumpButton) return;
    jumpButton.hidden = rootElement.scrollHeight - rootElement.scrollTop - rootElement.clientHeight <= 1;
  }
  function paintWindow() {
    if (!rootElement || !itemsElement) return;
    var previousAnchor = visibleAnchor();
    var viewport = rootElement.clientHeight || 1;
    var overscan = viewport * 2;
    var start = Math.max(0, lowerBound(Math.max(0, rootElement.scrollTop - overscan)));
    var end = Math.min(model.seqs.length, lowerBound(rootElement.scrollTop + viewport + overscan) + 1);
    topSpacer.style.height = prefix[start] + "px";
    bottomSpacer.style.height = Math.max(0, prefix[model.seqs.length] - prefix[end]) + "px";
    var wanted = Object.create(null);
    for (var i = start; i < end; i++) wanted[String(model.seqs[i])] = true;
    Object.keys(nodesBySeq).forEach(function (key) {
      if (!wanted[key]) { var oldNode = nodesBySeq[key]; if (oldNode.parentNode === itemsElement) itemsElement.removeChild(oldNode); delete nodesBySeq[key]; }
    });
    for (var itemIndex = start; itemIndex < end; itemIndex++) {
      var itemKey = String(model.seqs[itemIndex]);
      var itemEvent = model.bySeq[itemKey];
      var node = nodesBySeq[itemKey];
      if (!node || node.__canvasEvent !== itemEvent) {
        var replacement = render(itemEvent); replacement.__canvasEvent = itemEvent;
        if (node && node.parentNode === itemsElement) itemsElement.replaceChild(replacement, node);
        nodesBySeq[itemKey] = replacement; node = replacement;
      }
      var at = itemsElement.children[itemIndex - start];
      if (at !== node) itemsElement.insertBefore(node, at || null);
    }
    var nodes = itemsElement.querySelectorAll(".message");
    var changed = false;
    for (var n = 0; n < nodes.length; n++) {
      var seq = nodes[n].getAttribute("data-seq");
      var actual = nodes[n].getBoundingClientRect().height;
      if (actual > 0 && Math.abs((heights[seq] || 0) - actual) > 1) { heights[seq] = actual; changed = true; }
    }
    if (changed) {
      rebuildPrefix();
      if (previousAnchor) {
        var previousIndex = model.seqs.indexOf(previousAnchor.seq);
        if (previousIndex >= 0) rootElement.scrollTop = prefix[previousIndex] + previousAnchor.offset;
      }
    }
    var anchor = visibleAnchor();
    if (anchor) lastViewportAnchor = anchor;
    updateJumpButton();
  }
  function addAction(parent, label, action, seq, value) {
    var button = document.createElement("button");
    button.type = "button"; button.textContent = label; button.setAttribute("aria-label", label);
    button.addEventListener("click", function () { if (root.Android) root.Android.action(action, seq, value || ""); });
    parent.appendChild(button);
  }
  function render(event) {
    var article = document.createElement("article");
    article.className = "message " + String(event.kind || "result").replace(/[^a-z_]/g, "");
    article.setAttribute("role", "article"); article.setAttribute("data-seq", String(event.seq));
    article.setAttribute("aria-label", (event.kind === "user" ? "Tú" : event.kind === "assistant" ? "Asistente" : event.label || event.kind) + ": " + String(event.text || "").slice(0, 180));
    var label = document.createElement("div"); label.className = "label";
    label.textContent = event.label || (event.kind === "user" ? "Tú" : event.kind === "assistant" ? "Jarvys" : event.kind === "artifact" ? "Artefacto" : event.kind === "tool" ? "Herramienta" : event.kind);
    article.appendChild(label);
    var body = document.createElement("div"); body.className = "body";
    body.innerHTML = safeMarkdown(event.text || "");
    article.appendChild(body);
    (event.imageUrls || []).forEach(function (src) {
      var image = document.createElement("img"); image.src = src; image.alt = event.imageAlt || "Imagen adjunta"; image.loading = "lazy"; article.appendChild(image);
    });
    if (event.detail) { var detail = document.createElement("div"); detail.className = "tool-detail"; detail.textContent = event.detail; article.appendChild(detail); }
    if (event.kind === "artifact" && event.thumbnailUrl) {
      var thumb = document.createElement("img"); thumb.src = event.thumbnailUrl; thumb.alt = "Miniatura de " + (event.artifactTitle || event.text || "artefacto"); article.appendChild(thumb);
    }
    if (event.decisionOptions && event.decisionOptions.length) event.decisionOptions.forEach(function (option) {
      var button = document.createElement("button"); button.className = "decision-option"; button.type = "button";
      button.textContent = option.label; button.setAttribute("aria-label", option.label + (option.description ? ". " + option.description : ""));
      button.addEventListener("click", function () { if (root.Android) root.Android.action("resolve_decision", event.seq, option.id); });
      article.appendChild(button);
    });
    if (event.decisionStatus === "PENDING" && event.decisionAllowDismiss) {
      var dismiss = document.createElement("button"); dismiss.className = "decision-option"; dismiss.type = "button";
      dismiss.textContent = "Omitir"; dismiss.setAttribute("aria-label", "Omitir decisión");
      dismiss.addEventListener("click", function () { if (root.Android) root.Android.action("resolve_decision", event.seq, "dismiss"); });
      article.appendChild(dismiss);
    }
    var actions = document.createElement("div"); actions.className = "actions";
    if (event.canCopy) addAction(actions, "Copiar", "copy", event.seq);
    if (event.canRetry) addAction(actions, "Reintentar", "retry", event.seq);
    if (event.canOpen) addAction(actions, "Abrir visor", "open_viewer", event.seq);
    if (event.canTransfer) {
      addAction(actions, "Descargar", "download", event.seq);
      addAction(actions, "Compartir", "share", event.seq);
    }
    if (event.replies && !event.repliesUsed) event.replies.forEach(function (reply, index) {
      addAction(actions, reply.label, "respond", event.seq, String(index));
    });
    if (actions.childNodes.length) article.appendChild(actions);
    return article;
  }
  function applyDeltasToView(deltas) {
    var events = Array.isArray(deltas) ? deltas : (deltas && deltas.events) || [];
    var order = Array.isArray(deltas) ? null : deltas && deltas.order;
    if (!Array.isArray(deltas) && deltas && Object.prototype.hasOwnProperty.call(deltas, "loadingOlder"))
      setHistoryLoading(deltas.loadingOlder);
    if (!booted) { applyDeltas(model, events, order); return; }
    var wasAtEnd = rootElement && rootElement.scrollHeight - rootElement.scrollTop - rootElement.clientHeight <= 1;
    var before = visibleAnchor();
    applyDeltas(model, events, order);
    if (order) {
      var retainedHeights = Object.create(null);
      order.forEach(function (seq) { var key = String(seq); if (heights[key]) retainedHeights[key] = heights[key]; });
      heights = retainedHeights;
    }
    rebuildPrefix();
    if (wasAtEnd && !Array.isArray(deltas) && deltas.followEnd) {
      rootElement.scrollTop = rootElement.scrollHeight;
    } else if (before) {
      var index = model.seqs.indexOf(before.seq);
      if (index >= 0) rootElement.scrollTop = prefix[index] + before.offset;
    }
    schedulePaint();
  }
  function setHistoryLoading(loading) {
    if (!historyStatus) return;
    historyStatus.hidden = !loading;
    historyStatus.textContent = loading ? "Cargando mensajes…" : "";
  }
  function requestOlder() { if (root.Android) root.Android.requestOlderPage(); }
  function noteFrame(now) {
    if (!samplingFrames) return;
    if (lastFrame) frameTimes.push(now - lastFrame);
    lastFrame = now;
    requestAnimationFrame(noteFrame);
  }
  function percentile(values, percentile) {
    if (!values.length) return null;
    var sorted = values.slice().sort(function (a, b) { return a - b; });
    return sorted[Math.ceil(percentile * sorted.length) - 1];
  }
  function metrics() {
    return { firstPaintMs: firstPaintMs, meanFrameMs: frameTimes.length ? frameTimes.reduce(function (a, b) { return a + b; }, 0) / frameTimes.length : null,
      p95FrameMs: percentile(frameTimes, .95), frameCount: frameTimes.length, virtualizedNodes: itemsElement ? itemsElement.querySelectorAll(".message").length : 0,
      loadedMessages: model.seqs.length, patchCount: model.patches, fullRenders: model.fullRenders,
      anchorSeq: lastViewportAnchor ? lastViewportAnchor.seq : null, anchorOffset: lastViewportAnchor ? lastViewportAnchor.offset : null };
  }
  function runBenchmark() {
    if (!booted) { pendingBenchmark = true; return; }
    pendingBenchmark = false;
    frameTimes = []; lastFrame = 0; samplingFrames = true; requestAnimationFrame(noteFrame);
    var start = performance.now();
    var scrollMs = 0;
    var viewport = rootElement.clientHeight || 1;
    var distance = Math.max(0, rootElement.scrollHeight - viewport);
    rootElement.scrollTop = 0;
    requestAnimationFrame(function () {
      rootElement.scrollTo({ top: distance, behavior: "smooth" });
      var previousTop = -1, stableFrames = 0;
      function waitForScroll() {
        requestAnimationFrame(function () {
          if (Math.abs(rootElement.scrollTop - previousTop) <= 1) stableFrames++;
          else stableFrames = 0;
          previousTop = rootElement.scrollTop;
          if (stableFrames < 2 && Math.abs(distance - rootElement.scrollTop) > 1) { waitForScroll(); return; }
          samplingFrames = false;
          scrollMs = performance.now() - start;
          var anchor = visibleAnchor();
          var oldest = model.seqs.length ? model.seqs[0] : 0;
          var page = [];
          var pageSize = Math.max(1, itemsElement.querySelectorAll(".message").length);
          for (var i = 0; i < pageSize; i++) page.push({ seq: oldest - pageSize + i, kind: "assistant", text: "Página sintética anterior " + i });
          var pageStart = performance.now();
          applyDeltasToView({ events: page, order: page.map(function (event) { return event.seq; }).concat(model.seqs) });
          requestAnimationFrame(function () {
            var after = visibleAnchor();
            var deltaSeq = model.seqs[model.seqs.length - 1];
            var delta = model.bySeq[String(deltaSeq)];
            var deltaStart = performance.now();
            delta.text += " actualización";
            applyDeltasToView({ events: [delta] });
            requestAnimationFrame(function () {
              var result = metrics();
              result.scrollMs = scrollMs;
              result.pagingPaintMs = performance.now() - pageStart;
              result.streamDeltaPaintMs = performance.now() - deltaStart;
              result.anchorPreserved = !anchor || !after || (anchor.seq === after.seq && Math.abs(anchor.offset - after.offset) <= 1);
              result.virtualized = result.virtualizedNodes < result.loadedMessages;
              if (root.Android) root.Android.benchmarkResult(JSON.stringify(result));
            });
          });
        });
      }
      waitForScroll();
    });
  }
  if (typeof document !== "undefined") {
    document.addEventListener("DOMContentLoaded", function () {
      root.addEventListener("scroll", function (event) {
        if (event.target === rootElement && rootElement.scrollTop === 0) requestOlder();
      }, true);
    });
  }
  return { createModel: createModel, applyDeltas: applyDeltas, safeMarkdown: safeMarkdown, escapeHtml: escapeHtml,
    bootstrap: bootstrap, applyDeltasToView: applyDeltasToView, metrics: metrics, runBenchmark: runBenchmark };
}));
