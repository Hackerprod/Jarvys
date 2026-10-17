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

The tool takes one presentation object. Every slide must contain `elements`; the accepted structure is `slides[].elements[]` only. The former slide-level `title`, `body`, `bullets`, `shapes`, `images`, `chart`, and `table` format no longer exists. Give each element explicit `x`, `y`, `w`, and `h` coordinates; the current handler may derive `h` for tables.

For input or construction errors that you can correct, the handler returns `{ "success": false, "content": "..." }` with slide, element, and field context. Revise the named value and call the tool again. Uncaught exceptions are internal or runtime failures; their text is intentionally hidden by the host.

Use this presentation object:

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

These examples are illustrative, not templates. Each slide's `elements` array is ordered and each element uses explicit coordinates. The handler validates type-specific properties against PptxGenJS 4.0.1. Text `margin` is in points: a number applies to all sides; a four-number array is `[left, right, bottom, top]`. Table `margin` follows PptxGenJS table semantics: a scalar applies to each side; a four-number array is `[top, right, bottom, left]`; values below 1 are interpreted as inches and values at least 1 as points. Image `data:image/...;base64,...` payloads are decoded, and invalid image data is rejected. PptxGenJS 4.0.1 documents common PNG, JPG, and GIF inputs as well as SVG; SVG is stored with a PNG fallback in the PPTX. The bundled viewer and thumbnail renderer are verified with PNG. They omit SVG and the handler warns; other image formats have not been verified end to end in this viewer. Use PNG when in-app rendering must be dependable. Unknown properties are rejected with the slide and element location.

The handler accepts `type` values `text`, `shape`, `image`, `chart`, and `table`. Text can use `text` or rich-text `runs`; each run follows PptxGenJS's `{ "text": "...", "options": { ... } }` form. Run options can set `bold`, `italic`, `color`, `fontSize`, `fontFace`, and `hyperlink`. Shapes use PptxGenJS shape names. Charts use `chartType`, `labels`, and `datasets` with values aligned to labels; scatter charts are not accepted by this labels-and-values interface. Tables use `rows` and may specify column widths and row heights. Cell `colspan` and `rowspan` participate in the logical grid; a `rowspan` that extends beyond the final row is rejected with the cell location. Add slide speaker notes with `notes`.

An optional `theme` sets the presentation fonts and shared background. Its `colors` map supplies the selected chart palette when a chart does not declare its own colors. PptxGenJS 4.0.1 exposes font fields in its theme hook but keeps the OOXML theme color scheme fixed; set hex colors on text and shapes when they need the same chosen colors. With no theme, PptxGenJS defaults apply and the module adds no palette. Keep all content positions explicit.

## Illustrative compositions

These small examples show different ways to use the same free-positioned element model. Coordinates and styling are choices for their content, not required values.

### Cover with a decorative shape

```json
{
  "elements": [
    { "type": "shape", "shape": "ellipse", "x": 9.8, "y": 0.5, "w": 2.6, "h": 2.6, "fill": { "color": "DDEBFF" }, "line": { "color": "DDEBFF" } },
    { "type": "text", "text": "A clearer view of regional growth", "x": 2.0, "y": 2.0, "w": 9.3, "h": 1.4, "fontSize": 38, "bold": true, "align": "center", "color": "18253A" },
    { "type": "text", "text": "Quarterly outlook", "x": 3.4, "y": 3.65, "w": 6.5, "h": 0.6, "fontSize": 19, "align": "center", "color": "526176" }
  ]
}
```

### Two columns with text and an image

```json
{
  "elements": [
    { "type": "text", "text": "Local teams made the gains repeatable", "x": 0.8, "y": 1.1, "w": 3.5, "h": 2.0, "fontSize": 30, "bold": true, "valign": "middle" },
    { "type": "text", "text": "North and South used the same field playbook, then adapted it to local demand.", "x": 0.8, "y": 3.45, "w": 3.5, "h": 2.1, "fontSize": 19, "valign": "top" },
    { "type": "image", "dataUrl": "data:image/png;base64,...", "x": 4.8, "y": 0.8, "w": 7.6, "h": 5.8, "sizing": "contain", "altText": "Regional team at work" }
  ]
}
```

### Chart beside three insights

```json
{
  "elements": [
    { "type": "chart", "chartType": "bar", "labels": ["North", "South", "West"], "datasets": [{ "name": "Revenue", "values": [42, 57, 39] }], "x": 0.9, "y": 0.6, "w": 7.0, "h": 5.4, "options": { "showValue": true, "showLegend": false } },
    { "type": "text", "text": "South reached the highest total", "x": 8.5, "y": 1.1, "w": 3.9, "h": 0.9, "fontSize": 21, "bold": true },
    { "type": "text", "text": "North grew from its lower starting point", "x": 8.5, "y": 2.65, "w": 3.9, "h": 0.9, "fontSize": 21 },
    { "type": "text", "text": "West remained close to the prior quarter", "x": 8.5, "y": 4.2, "w": 3.9, "h": 0.9, "fontSize": 21 },
    { "type": "text", "text": "Regional revenue rose across the quarter", "x": 2.1, "y": 6.25, "w": 9.2, "h": 0.65, "fontSize": 25, "bold": true, "align": "center" }
  ]
}
```

### Table led by a large figure (no title)

```json
{
  "elements": [
    { "type": "text", "text": "+27%", "x": 0.9, "y": 0.6, "w": 3.6, "h": 1.7, "fontSize": 52, "bold": true, "color": "2D6A72" },
    { "type": "text", "text": "Regional revenue growth", "x": 0.95, "y": 2.15, "w": 4.5, "h": 0.5, "fontSize": 18, "color": "526176" },
    { "type": "table", "rows": [["Region", "Revenue", "Change"], ["North", "$42M", "+18%"], ["South", "$57M", "+27%"], ["West", "$39M", "+8%"]], "x": 5.2, "y": 0.85, "w": 7.3, "h": 4.8, "colW": [2.8, 2.3, 2.2], "fontSize": 18 }
  ]
}
```

### Quote

```json
{
  "elements": [
    { "type": "shape", "shape": "line", "x": 0.8, "y": 5.6, "w": 11.7, "h": 0, "line": { "color": "AAB4C4", "width": 1 } },
    { "type": "text", "text": "“The same playbook gave each team room to respond to its market.”", "x": 1.3, "y": 1.0, "w": 9.8, "h": 2.4, "fontSize": 31, "italic": true, "align": "center", "valign": "middle" },
    { "type": "text", "text": "FIELD OPERATIONS LEAD", "x": 7.2, "y": 5.95, "w": 4.8, "h": 0.5, "fontSize": 15, "align": "right", "color": "526176" }
  ]
}
```

## Use the measurement report to revise

The handler returns the editable artifact and a compact JSON measurement report in the tool result. Elements are identified by slide, index, and type; a box is included for elements with findings. Text entries include required height, line count, and contrast as a number, `{ratio, runs}`, or `null` with a reason. The report lists overlaps, out-of-bounds elements, and the smallest font size used per slide.

- If `overflow` is true, compare `needed_h` with the input box's `h`; increase the element height, shorten the wording, or divide the content across slides.
- If two element boxes overlap, move or resize one of the elements if that intersection is unintended.
- If an element is outside the slide bounds, revise its coordinates or dimensions to fit the 13.333 × 7.5 inch canvas.
- Review contrast and the reported smallest text size in the actual viewing context; the report gives measurements, while you decide whether the composition reads well.
- A measured overlap or overflow is feedback, not a generation failure. Regenerate with changed coordinates or content when the evidence calls for it.

The report identifies elements by slide number, zero-based element index, and type; it does not repeat input text. Each element includes measured height and line count for text. A box and overflow flag appear only when a finding needs review; low or unknown contrast, overlaps, and out-of-bounds boxes are listed with the relevant elements. `contrast` is a number when all run ratios agree, an object `{ratio, runs}` when runs have different ratios, or `null` with `contrast_reason` when a ratio cannot be determined. Reports also identify the metrics source (`canvas-font-metrics` in WebView; deterministic width fallback in the Node contract). The browser canvas uses the fonts available to the WebView. A different viewer or font installation can wrap text differently, so inspect the rendered deck as well as the report.

### Accepted element properties

Every element requires `type`, `x`, `y`, and `w`. Text, shape, image, and chart elements also require `h`; tables may omit `h`, which PptxGenJS derives from their rows and optional `rowH`. The lists below show the secondary properties accepted by the handler; fill, line, hyperlink, and other compound values are validated too. The `shape` property selects a PptxGenJS shape name. A text run has `text` and optional `options`; run options are limited to the documented style fields.

- `text`: `text`, `runs`, `align`, `bold`, `breakLine`, `bullet`, `color`, `fontFace`, `fontSize`, `highlight`, `italic`, `lang`, `softBreakBefore`, `tabStops`, `textDirection`, `transparency`, `underline`, `valign`, `baseline`, `charSpacing`, `fit`, `fill`, `flipH`, `flipV`, `glow`, `hyperlink`, `indentLevel`, `isTextBox`, `line`, `lineSpacing`, `lineSpacingMultiple`, `margin`, `outline`, `paraSpaceAfter`, `paraSpaceBefore`, `placeholder`, `rectRadius`, `rotate`, `rtlMode`, `shadow`, `shape`, `strike`, `subscript`, `superscript`, `vert`, `wrap`, `autoFit`, `shrinkText`, `inset`, `lineDash`, `lineHead`, `lineSize`, `lineTail`, `objectName`
- `textRun`: `text`, `options`
- `textRunOptions`: `bold`, `italic`, `color`, `fontSize`, `fontFace`, `hyperlink`
- `shape`: `shape`, `align`, `angleRange`, `arcThicknessRatio`, `fill`, `flipH`, `flipV`, `hyperlink`, `line`, `points`, `rectRadius`, `rotate`, `shadow`, `lineSize`, `lineDash`, `lineHead`, `lineTail`, `objectName`
- `image`: `dataUrl`, `sizing`, `altText`, `flipH`, `flipV`, `hyperlink`, `rotate`, `rounding`, `shadow`, `transparency`, `objectName`
- `chart`: `chartType`, `labels`, `datasets`, `options`
- `chartOptions`: `objectName`, `axisPos`, `chartColors`, `chartColorsOpacity`, `dataBorder`, `displayBlanksAs`, `invertedColors`, `lang`, `layout`, `shadow`, `showLabel`, `showLeaderLines`, `showLegend`, `showPercent`, `showSerName`, `showTitle`, `showValue`, `v3DPerspective`, `v3DRAngAx`, `v3DRotX`, `v3DRotY`, `chartArea`, `plotArea`, `border`, `fill`, `catAxes`, `catAxisBaseTimeUnit`, `catAxisCrossesAt`, `catAxisHidden`, `catAxisLabelColor`, `catAxisLabelFontBold`, `catAxisLabelFontFace`, `catAxisLabelFontItalic`, `catAxisLabelFontSize`, `catAxisLabelFrequency`, `catAxisLabelPos`, `catAxisLabelRotate`, `catAxisLineColor`, `catAxisLineShow`, `catAxisLineSize`, `catAxisLineStyle`, `catAxisMajorTickMark`, `catAxisMajorTimeUnit`, `catAxisMajorUnit`, `catAxisMaxVal`, `catAxisMinorTickMark`, `catAxisMinorTimeUnit`, `catAxisMinorUnit`, `catAxisMinVal`, `catAxisMultiLevelLabels`, `catAxisOrientation`, `catAxisTitle`, `catAxisTitleColor`, `catAxisTitleFontFace`, `catAxisTitleFontSize`, `catAxisTitleRotate`, `catGridLine`, `catLabelFormatCode`, `secondaryCatAxis`, `showCatAxisTitle`, `serAxes`, `serAxisBaseTimeUnit`, `serAxisHidden`, `serAxisLabelColor`, `serAxisLabelFontBold`, `serAxisLabelFontFace`, `serAxisLabelFontItalic`, `serAxisLabelFontSize`, `serAxisLabelFrequency`, `serAxisLabelPos`, `serAxisLineColor`, `serAxisLineShow`, `serAxisMajorTimeUnit`, `serAxisMajorUnit`, `serAxisMinorTimeUnit`, `serAxisMinorUnit`, `serAxisOrientation`, `serAxisTitle`, `serAxisTitleColor`, `serAxisTitleFontFace`, `serAxisTitleFontSize`, `serAxisTitleRotate`, `serGridLine`, `serLabelFormatCode`, `showSerAxisTitle`, `secondaryValAxis`, `showValAxisTitle`, `valAxes`, `valAxisCrossesAt`, `valAxisDisplayUnit`, `valAxisDisplayUnitLabel`, `valAxisHidden`, `valAxisLabelColor`, `valAxisLabelFontBold`, `valAxisLabelFontFace`, `valAxisLabelFontItalic`, `valAxisLabelFontSize`, `valAxisLabelFormatCode`, `valAxisLabelPos`, `valAxisLabelRotate`, `valAxisLineColor`, `valAxisLineShow`, `valAxisLineSize`, `valAxisLineStyle`, `valAxisLogScaleBase`, `valAxisMajorTickMark`, `valAxisMajorUnit`, `valAxisMaxVal`, `valAxisMinorTickMark`, `valAxisMinVal`, `valAxisOrientation`, `valAxisTitle`, `valAxisTitleColor`, `valAxisTitleFontFace`, `valAxisTitleFontSize`, `valAxisTitleRotate`, `valGridLine`, `valLabelFormatCode`, `bar3DShape`, `barDir`, `barGapDepthPct`, `barGapWidthPct`, `barGrouping`, `barOverlapPct`, `dataNoEffects`, `holeSize`, `lineCap`, `lineDash`, `lineDataSymbol`, `lineDataSymbolLineColor`, `lineDataSymbolLineSize`, `lineDataSymbolSize`, `lineSize`, `lineSmooth`, `firstSliceAng`, `radarStyle`, `dataLabelBkgrdColors`, `dataLabelColor`, `dataLabelFontBold`, `dataLabelFontFace`, `dataLabelFontItalic`, `dataLabelFontSize`, `dataLabelFormatCode`, `dataLabelFormatScatter`, `dataLabelPosition`, `dataTableFontSize`, `dataTableShowHorzBorder`, `dataTableShowKeys`, `dataTableShowOutline`, `dataTableShowVertBorder`, `legendColor`, `legendFontBold`, `legendFontFace`, `legendFontItalic`, `legendFontSize`, `legendPos`, `showDataTable`, `showDataTableHorzBorder`, `showDataTableKeys`, `showDataTableOutline`, `showDataTableVertBorder`, `title`, `titleColor`, `titleFontBold`, `titleFontFace`, `titleFontItalic`, `titleFontSize`, `titlePos`, `showCatName`
- `table`: `rows`, `align`, `bold`, `breakLine`, `bullet`, `color`, `fontFace`, `fontSize`, `highlight`, `italic`, `lang`, `margin`, `paraSpaceAfter`, `paraSpaceBefore`, `valign`, `underline`, `border`, `colW`, `fill`, `rowH`, `transparency`, `rotate`, `rtlMode`, `vert`, `wrap`, `objectName`
- `tableCell`: `align`, `bold`, `breakLine`, `bullet`, `color`, `fontFace`, `fontSize`, `highlight`, `italic`, `lang`, `margin`, `paraSpaceAfter`, `paraSpaceBefore`, `valign`, `underline`, `border`, `fill`, `colspan`, `rowspan`, `rotate`, `transparency`, `hyperlink`, `line`, `shadow`, `fit`, `wrap`, `vert`, `strike`, `subscript`, `superscript`, `outline`

For contrast, a known background and one shared run ratio produce a number. If runs have different ratios, `contrast` is an object with the minimum `ratio` and per-run results. When the background cannot be determined, `contrast` is `null` with `contrast_reason`. A shape that partly covers a text box reports `background is mixed`; a fully covering opaque shape uses its fill. Transparent or semitransparent fills, images, charts, and tables behind the text report `null` with a reason rather than an estimated ratio.
