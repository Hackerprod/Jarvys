"use strict";

module.exports = (pngDataUrl) => [
  {
    key: "free-cover",
    title: "A clearer view of regional growth",
    slides: [{
      background: "F7F8F5",
      notes: "Cover composition with separate text and decorative geometry.",
      elements: [
        { type: "shape", shape: "ellipse", x: 9.8, y: 0.5, w: 2.5, h: 2.5, fill: { color: "DDEBFF" }, line: { color: "DDEBFF" } },
        { type: "shape", shape: "rect", x: 8.8, y: 3.9, w: 3.1, h: 1.5, fill: { color: "2D6A72" }, line: { color: "2D6A72" }, rotate: -8 },
        { type: "text", runs: [{ text: "A clearer view of ", bold: true, color: "18253A" }, { text: "regional growth", color: "2D6A72" }], x: 0.9, y: 1.9, w: 8.0, h: 1.35, fontSize: 34, fontFace: "Arial", margin: 0 },
        { type: "text", text: "Quarterly outlook | October 2026", x: 0.95, y: 3.55, w: 6.8, h: 0.55, fontSize: 18, fontFace: "Arial", color: "526176", margin: 0 },
      ],
    }],
  },
  {
    key: "free-columns",
    title: "Local teams shared a field playbook",
    slides: [{
      elements: [
        { type: "text", text: "Local teams shared a field playbook", x: 0.8, y: 0.6, w: 11.7, h: 0.9, fontSize: 30, fontFace: "Arial", color: "18253A", bold: true, margin: 0 },
        { type: "text", text: "North and South used the same approach, then adapted it to local demand. The shared method made the gains repeatable.", x: 0.9, y: 2.0, w: 5.0, h: 2.2, fontSize: 20, fontFace: "Arial", color: "344054", valign: "top", margin: 0 },
        { type: "image", dataUrl: pngDataUrl(), x: 6.7, y: 1.8, w: 5.6, h: 4.3, sizing: "contain", altText: "Illustrated mountain landscape beneath a bright sky" },
        { type: "text", text: "A consistent method, adapted locally", x: 6.7, y: 6.25, w: 5.6, h: 0.45, fontSize: 16, fontFace: "Arial", color: "526176", align: "center", margin: 0 },
      ],
    }],
  },
  {
    key: "free-chart-insights",
    title: "South led quarterly regional revenue",
    slides: [{
      elements: [
        { type: "text", text: "South led quarterly regional revenue", x: 0.8, y: 0.5, w: 11.7, h: 0.9, fontSize: 30, fontFace: "Arial", color: "18253A", bold: true, margin: 0 },
        { type: "chart", chartType: "bar", labels: ["North", "South", "West"], datasets: [{ name: "Revenue", values: [42, 57, 39], color: "2D6A72" }], x: 0.8, y: 1.7, w: 6.0, h: 4.9, options: { title: "Quarterly revenue", showTitle: true, showValue: true, showLegend: false, valAxisLabelFormatCode: "$0" } },
        { type: "text", text: "South reached the highest total", x: 7.4, y: 1.9, w: 4.7, h: 0.9, fontSize: 21, fontFace: "Arial", color: "18253A", bold: true, margin: 0 },
        { type: "text", text: "North delivered the next highest result", x: 7.4, y: 3.2, w: 4.7, h: 0.9, fontSize: 21, fontFace: "Arial", color: "18253A", margin: 0 },
        { type: "text", text: "West remained near its prior quarter", x: 7.4, y: 4.5, w: 4.7, h: 0.9, fontSize: 21, fontFace: "Arial", color: "18253A", margin: 0 },
      ],
    }],
  },
  {
    key: "free-table-long-title",
    title: "Every region improved while operating costs stayed within plan",
    slides: [{
      notes: ["Regional operating report", "Values reviewed with the finance team"],
      elements: [
        { type: "text", text: "Every region improved while operating costs stayed within plan", x: 0.8, y: 0.4, w: 11.5, h: 1.35, fontSize: 29, fontFace: "Arial", color: "18253A", bold: true, margin: 0 },
        { type: "text", text: "Quarterly operating summary", x: 0.8, y: 1.9, w: 8.0, h: 0.5, fontSize: 18, fontFace: "Arial", color: "526176", margin: 0 },
        { type: "text", text: "Revenue and operating cost change by region", x: 0.8, y: 2.55, w: 8.0, h: 0.45, fontSize: 16, fontFace: "Arial", color: "344054", margin: 0 },
        { type: "table", rows: [["Region", "Revenue", "Change"], ["North", "$42M", "+18%"], ["South", "$57M", "+27%"], ["West", "$39M", "+8%"]], x: 0.8, y: 3.2, w: 11.6, colW: [4.4, 3.6, 3.6], rowH: [0.65, 0.65, 0.65, 0.65], fontSize: 18, fontFace: "Arial", color: "18253A", margin: 0.08, border: { type: "solid", color: "D5DCE5", pt: 0.75 } },
      ],
    }],
  },
];
