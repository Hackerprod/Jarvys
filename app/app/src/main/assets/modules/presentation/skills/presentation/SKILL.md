---
id: jarvys-presentation
name: jarvys-presentation
description: Compose editable PowerPoint slides with explicit, measured layouts using create_presentation.
version: 1
allowed-tools: [create_presentation]
tags: [presentation, composition]
---

# Compose the slides yourself

`create_presentation` constructs the elements you specify. It does not place titles, body text, bullets, images, charts, or tables for you. Choose each element's position and size, then use the returned measurements to decide whether to revise the deck.

## Canvas and composition

The slide format is PptxGenJS `LAYOUT_WIDE`: 13.333 × 7.5 inches. Coordinates start at the top-left; `x`, `y`, `w`, and `h` are inches. Use the whole canvas deliberately: decide the margins, columns, alignment lines, and visual grouping that serve this slide's content. There is no required grid or layout.

For each slide:

- Give it one clear purpose. Let its title identify the subject or express a supported finding.
- Make the main idea easy to find through size, weight, position, or contrast. Keep supporting text secondary.
- Keep text concise and at a size that remains readable in the target viewing context. Prefer editing or rearranging content over compressing it until it is hard to read.
- Leave intentional breathing room. Align related edges and baselines; keep labels close to the content they explain.
- Use enough contrast between text and its background. When both colors are known, the report includes the measured contrast ratio.
- Use charts to show relationships and give categories and series meaningful labels. Enable data labels when values need to be read directly.
- Keep tables legible: use a clear header, enough room for each cell, and restrained borders or fills.
- Use empty space as part of the composition. It can separate groups and give the main point emphasis.
- Keep visual language consistent across slides while allowing their compositions to fit their different purposes.

## Input contract

The tool takes one presentation object:

```json
{
  "title": "Regional outlook",
  "theme": {
    "fonts": { "headFontFace": "Aptos Display", "bodyFontFace": "Aptos" },
    "colors": { "accent1": "2D6A72", "accent2": "D17B45" },
    "background": "F7F8F5"
  },
  "slides": [
    {
      "background": { "color": "FFFFFF" },
      "notes": "Source notes for this slide",
      "elements": [
        { "type": "text", "text": "Regional outlook", "x": 0.8, "y": 0.7, "w": 8.4, "h": 1.2, "fontSize": 34, "bold": true },
        { "type": "shape", "shape": "line", "x": 0.8, "y": 2.1, "w": 8.4, "h": 0, "line": { "color": "AAB4C4", "width": 1 } }
      ]
    }
  ]
}
```

The example is illustrative, not a default layout. Each slide's `elements` array is ordered and each element uses explicit coordinates. Text and shape options follow the supported PptxGenJS 4.0.1 options listed by `create_presentation`; images use a decodable PNG data URL; charts and tables also require a box. Unknown properties are rejected with the slide and element location.

Text can use `text` or rich-text `runs`. Shapes use PptxGenJS shape names. Charts use `chartType`, `labels`, and `datasets` with values aligned to labels. Tables use `rows` and may specify column widths and row heights. Add slide speaker notes with `notes`.

An optional `theme` sets the presentation fonts and shared background. Its `colors` map supplies the selected chart palette when a chart does not declare its own colors. PptxGenJS 4.0.1 exposes font fields in its theme hook but keeps the OOXML theme color scheme fixed; set hex colors on text and shapes when they need the same chosen colors. With no theme, PptxGenJS defaults apply and the module adds no palette. Keep all content positions explicit.

## Illustrative compositions

These small examples show different ways to use the same free-positioned element model. Coordinates and styling are choices for their content, not required values.

### Cover with a decorative shape

```json
{
  "elements": [
    { "type": "shape", "shape": "ellipse", "x": 9.8, "y": 0.5, "w": 2.6, "h": 2.6, "fill": { "color": "DDEBFF" }, "line": { "color": "DDEBFF" } },
    { "type": "text", "text": "A clearer view of regional growth", "x": 0.9, "y": 2.1, "w": 8.5, "h": 1.8, "fontSize": 38, "bold": true, "color": "18253A" },
    { "type": "text", "text": "Quarterly outlook", "x": 0.95, "y": 4.2, "w": 6.4, "h": 0.6, "fontSize": 19, "color": "526176" }
  ]
}
```

### Two columns with text and an image

```json
{
  "elements": [
    { "type": "text", "text": "Local teams made the gains repeatable", "x": 0.8, "y": 0.6, "w": 11.7, "h": 0.9, "fontSize": 30, "bold": true },
    { "type": "text", "text": "North and South used the same field playbook, then adapted it to local demand.", "x": 0.9, "y": 2.0, "w": 5.0, "h": 2.2, "fontSize": 20, "valign": "top" },
    { "type": "image", "dataUrl": "data:image/png;base64,...", "x": 6.7, "y": 1.8, "w": 5.6, "h": 4.3, "sizing": "contain", "altText": "Regional team at work" }
  ]
}
```

### Chart beside three insights

```json
{
  "elements": [
    { "type": "text", "text": "North and South led the quarter", "x": 0.8, "y": 0.5, "w": 11.8, "h": 0.9, "fontSize": 30, "bold": true },
    { "type": "chart", "chartType": "bar", "labels": ["North", "South", "West"], "datasets": [{ "name": "Revenue", "values": [42, 57, 39] }], "x": 0.8, "y": 1.7, "w": 6.0, "h": 4.9, "options": { "showValue": true, "showLegend": false } },
    { "type": "text", "text": "South reached the highest total", "x": 7.4, "y": 1.9, "w": 4.7, "h": 0.9, "fontSize": 21, "bold": true },
    { "type": "text", "text": "North grew from its lower starting point", "x": 7.4, "y": 3.2, "w": 4.7, "h": 0.9, "fontSize": 21 },
    { "type": "text", "text": "West remained close to the prior quarter", "x": 7.4, "y": 4.5, "w": 4.7, "h": 0.9, "fontSize": 21 }
  ]
}
```

### Table under a two-line title

```json
{
  "elements": [
    { "type": "text", "text": "Every region improved while operating costs stayed within plan", "x": 0.8, "y": 0.45, "w": 10.4, "h": 1.35, "fontSize": 29, "bold": true },
    { "type": "text", "text": "Quarterly operating summary", "x": 0.8, "y": 2.0, "w": 7.2, "h": 0.55, "fontSize": 18 },
    { "type": "table", "rows": [["Region", "Revenue", "Change"], ["North", "$42M", "+18%"], ["South", "$57M", "+27%"], ["West", "$39M", "+8%"]], "x": 0.8, "y": 2.8, "w": 11.6, "h": 3.7, "colW": [4.4, 3.6, 3.6], "fontSize": 18 }
  ]
}
```

### Quote

```json
{
  "elements": [
    { "type": "text", "text": "“The same playbook gave each team room to respond to its market.”", "x": 1.3, "y": 1.5, "w": 9.8, "h": 2.4, "fontSize": 31, "italic": true, "align": "center", "valign": "middle" },
    { "type": "text", "text": "Field operations lead", "x": 3.1, "y": 4.4, "w": 6.2, "h": 0.6, "fontSize": 18, "align": "center", "color": "526176" }
  ]
}
```

## Use the measurement report to revise

The handler returns the editable artifact and a JSON measurement report in the tool result. Each element report includes its requested box and type. Text reports include measured required height and box height, plus `overflow`. The report also lists intersecting element pairs, out-of-bounds elements, the smallest font size used, and contrast ratios when text and background colors are known.

- If `overflow` is true, compare `needed_h` with `box_h`; increase the element height, shorten the wording, or divide the content across slides.
- If two element boxes overlap, move or resize one of the elements if that intersection is unintended.
- If an element is outside the slide bounds, revise its coordinates or dimensions to fit the 13.333 × 7.5 inch canvas.
- Review contrast and the reported smallest text size in the actual viewing context; the report gives measurements, while you decide whether the composition reads well.
- A measured overlap or overflow is feedback, not a generation failure. Regenerate with changed coordinates or content when the evidence calls for it.

The browser canvas uses the fonts available to the WebView. A different viewer or font installation can wrap text differently, so inspect the rendered deck as well as the report.
