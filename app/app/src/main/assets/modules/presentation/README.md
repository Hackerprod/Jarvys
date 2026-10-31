# Presentation module

`create_presentation` builds a PowerPoint from agent-authored elements. The module does not choose a layout or position content for the agent. Every slide must use `slides[].elements[]`; the old slide-level `title`, `body`, `bullets`, `shapes`, `images`, `chart`, and `table` format is no longer supported. Elements declare explicit `x`, `y`, `w`, and `h` coordinates in inches on the `LAYOUT_WIDE` canvas (13.333 × 7.5 in). Tables may omit `h` where the handler derives it from their rows.

The input is `{ title, theme?, slides: [{ background?, notes?, elements }] }`, and each slide requires its `elements` array. See `manifest.json` for the accepted fields and `skills/presentation/SKILL.md` for composition guidance and examples. Element properties are passed to PptxGenJS 4.0.1, subject to input validation.

On success, the handler delivers the editable `.pptx` and first-slide PNG thumbnail through the existing artifact capability. Its JSON tool content also includes the artifact ID and a compact per-slide/per-element measurement report: text height and line count measured with the WebView canvas, contrast when determinable, and overlap or bounds findings. Geometry appears for elements that need review. These are measurements for the agent to interpret; overlap or text overflow does not prevent delivery.

The viewer and thumbnail renderer remain unchanged. SVG data URLs are reported as unsupported because the viewer/thumbnail path does not render them reliably. With no theme supplied, the module leaves PptxGenJS defaults in place. An optional theme can set `fonts.headFontFace`, `fonts.bodyFontFace`, `colors` (the agent-selected chart palette), and a shared background; set hex colors directly on text and shapes because PptxGenJS 4.0.1 does not expose replacement of its OOXML theme color scheme. The module supplies no palette of its own.

## Handler error contract

Return `{ success: false, content: "..." }` for input or construction errors the agent can correct, with the relevant slide, element, and field. Module authors should throw exceptions only for internal or runtime failures; the host deliberately keeps exception text from the agent.
