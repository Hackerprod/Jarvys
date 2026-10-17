# Presentation module

`create_presentation` builds a PowerPoint from agent-authored elements. The module does not choose a layout or position content for the agent. Every slide element has explicit `x`, `y`, `w`, and `h` coordinates in inches on the `LAYOUT_WIDE` canvas (13.333 × 7.5 in).

The input is `{ title, theme?, slides }`. A slide can set `background`, `notes`, and an ordered `elements` array containing text, shapes, images, charts, or tables. See `manifest.json` for the accepted fields and `skills/presentation/SKILL.md` for composition guidance and examples. Element properties are passed to PptxGenJS 4.0.1, subject to input validation.

On success, the handler delivers the editable `.pptx` and first-slide PNG thumbnail through the existing artifact capability. Its JSON tool content also includes the artifact ID and a per-slide/per-element measurement report: requested boxes, text fit measured with the WebView canvas, overlaps, bounds, smallest text size, and known text/background contrast. These are measurements for the agent to interpret; overlap or text overflow does not prevent delivery.

The viewer and thumbnail renderer remain unchanged. SVG data URLs are reported as unsupported because the viewer/thumbnail path does not render them reliably. With no theme supplied, the module leaves PptxGenJS defaults in place. An optional theme can set `fonts.headFontFace`, `fonts.bodyFontFace`, `colors` (the agent-selected chart palette), and a shared background; set hex colors directly on text and shapes because PptxGenJS 4.0.1 does not expose replacement of its OOXML theme color scheme. The module supplies no palette of its own.
