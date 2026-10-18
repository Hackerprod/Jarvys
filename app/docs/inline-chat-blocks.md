# Inline chat blocks

Assistant Markdown fences are dispatched by the language-to-renderer registry in `InlineBlockRenderers.kt`. SVG, chart, and LaTeX blocks draw natively in Compose. Ordinary Markdown remains on the existing renderer path, which includes GFM tables and applies horizontal scrolling when a table is wider than its measured message area. Invalid or unfinished final special fences fall back to copyable code.

## SVG fidelity

The matrix below records alpha bounds and paint measured for generated 160 x 80 SVG samples with AndroidSVG 1.4 in Robolectric native graphics (API 34) and headless Chromium. The shared line/arrow endpoint is included in the measured region; `refX=10` and marker-start cases use a region around the marker endpoint.

| Variant | AndroidSVG 1.4 | Chrome headless | Measured result |
| --- | --- | --- | --- |
| `markerUnits=strokeWidth`, 10 x 10 `viewBox`, `refX=0`, `refY=5`, `orient=auto`, filled `currentColor`, stroke 1.5 | 15 x 15 px at (80, 33), `#2468AC` | Same | Marker geometry scales with the referring stroke. |
| `markerUnits=userSpaceOnUse`, stroke 1.5 | 10 x 10 px at (80, 35), `#2468AC` | Same | User-space size ignores stroke width. |
| `viewBox` omitted, dimensions present | 15 x 15 px, `#2468AC` | Same | No difference in this sample. |
| `markerWidth/Height` omitted, with and without `viewBox` | 5 x 4 px, `#2468AC` | Same | Default marker dimensions are 3 x 3 and scale with `strokeWidth`. |
| `refX/refY` omitted | 15 x 15 px at (80, 40), `#2468AC` | Same | The default reference point is applied. |
| `refX=10`, `refY=5` | 15 x 15 px at (65, 33), `#2468AC` | Same | The reference point changes placement, not dimensions. |
| Filled path with `fill=currentColor` | 15 x 15 px, `#202020` | Same | Explicit `currentColor` follows SVG `color`. |
| Path with omitted `fill`, `color=#F0F0F0`, target stroke `#D02030` | 15 x 15 px black | Same | SVG's default fill is black. |
| `fill=none; stroke=currentColor; stroke-width=1`, via inline, marker-path CSS, and class CSS | 15 x 15 px outline, `#2468AC` | Same bounds and paint | AndroidSVG resolves these marker styles. |
| `fill=context-stroke` | 15 x 15 px black | 15 x 15 px, `#D02030` | AndroidSVG 1.4 does not support `context-stroke`; it leaves the default fill. Chromium resolves the contextual paint. |
| `fill=none; stroke=context-fill; stroke-width=1` | Empty marker | 15 x 15 px outline, `#A030D0` | AndroidSVG 1.4 does not support `context-fill`; Chromium resolves the contextual paint. |
| Marker on `line`, `path`, and `polyline` | 15 x 15 px each | Same | No difference for these referencing elements. |
| Stroke width 1 / 1.5 / 2 with `markerUnits=strokeWidth` | 10 x 10 / 15 x 15 / 20 x 20 px | Same | Size scales linearly with the referring stroke. |
| Light/dark `color` with explicit `fill=currentColor` | 15 x 15 px, `#202020` / `#F0F0F0` | Same colors and bounds | Explicit `currentColor` follows `color`. |
| `orient=auto-start-reverse` on `marker-end` | Render rejected; inline block falls back to code | 15 x 15 px at (80, 33) | AndroidSVG 1.4 does not support this orientation; Chromium does. |
| `orient=auto` / `auto-start-reverse` on `marker-start` | `auto`: 16 x 15 px at (20, 33); reverse rejected | `auto`: 16 x 15 px; reverse: 15 x 15 px at (5, 33) | `auto` renders in both; AndroidSVG 1.4 does not support `auto-start-reverse`. |

These measurements confirm `strokeWidth` scaling, default 3 x 3 marker dimensions, `viewBox` transforms, `refX/refY`, `orient=auto`, explicit CSS marker styles, and line/path/polyline references for the tested samples. AndroidSVG 1.4 does not support `auto-start-reverse`, `context-stroke`, or `context-fill`; the observed outcomes are render rejection or unsupported-paint defaults. These are measured library limits; this document makes no claim that they are being fixed.

The owner's real OMEGA SVG uses `<marker markerWidth="10" markerHeight="10" refX="8" refY="5" orient="auto">` with no `markerUnits` or marker `viewBox`, on a path with `stroke-width="2.5"`. SVG's default is `markerUnits="strokeWidth"`, so the 10-unit head scales to 25 viewBox units. Chromium draws the same head size. With `markerUnits="userSpaceOnUse"`, it measures 10 units, 2.5 times smaller. `OmegaSvgRenderingTest` keeps this behavior with API 34 native rendering and pixel measurements of approximately 25 and 10 viewBox units.

For inline arrows, give the SVG a `viewBox` and explicit marker dimensions and reference points. The default `markerUnits="strokeWidth"` ties the head size to the line; use `userSpaceOnUse` and a marker `viewBox` for a fixed head size. Use `orient="auto"`, `fill="currentColor"` for a filled head, or `fill="none"` with `stroke="currentColor"` for an outline. Keep labels legible on portrait phones and sized to fit their shapes.

## Prompt guidance

`VISUAL RESPONSES` says that chat renders `svg`, `chart`, and `latex` fences inline. It asks for a compact figure right after the sentence it illustrates when a structure, flow, spatial layout, or magnitude comparison is clearer visually, with a text explanation; prose and tables handle other content. It specifies the chart JSON shape and data provenance, `viewBox`, a transparent background, `currentColor`, the 25-unit default OMEGA marker example, fixed-size marker guidance, LaTeX fences, and module tools for files such as PPTX or PNG.

## Dependencies

- `com.caverock:androidsvg-aar:1.4` - Apache-2.0. AndroidSVG draws sanitized SVG into Compose Canvas. The Maven Central AAR is 202,395 bytes.
- `io.github.huarangmeng:latex-renderer-android:1.5.6-kt2.1.0` - MIT. Compose Latex renders fenced equations with Compose Canvas and includes KaTeX 0.16.11 TTF fonts, also MIT. The renderer, parser, and base Android AARs total 1,668,515 bytes. Its documented compatibility variants do not exactly match this app's Kotlin 2.2.20 and Compose UI 1.9.0 toolchain; this selected artifact variant declares Kotlin 2.2.10 and Compose UI 1.9.3 and must remain covered by app builds.

The LaTeX integration accepts fenced `latex` and `tex` source. It validates parser diagnostics before rendering, wraps long equations to the message width, follows Material 3 colors, and enables the library's MathSpeak TalkBack description. `$$...$$` remains on the Markdown renderer path because that renderer does not provide LaTeX math support.

Before publishing, include AndroidSVG 1.4 (Apache-2.0), Compose Latex 1.5.6-kt2.1.0 (MIT), and its KaTeX font notices (MIT) in the app's Open-source licenses section. Also include all 17 packages in the presentation viewer bundle: `pptx-glimpse` 5.3.1, `@pptx-glimpse/document` 0.16.0, `@pptx-glimpse/editor` 0.6.0, `fflate` 0.8.3, `@xmldom/xmldom` 0.9.12, `rtf.js` 3.0.9, `fast-xml-parser` 5.11.2, `is-unsafe` 2.0.2, `xml-naming` 0.3.0, `fast-xml-builder` 1.3.1, `@nodable/entities` 3.1.0, `anynum` 1.0.1, `strnum` 2.5.1, `path-expression-matcher` 1.6.2, `opentype.js` 1.3.4, `tiny-inflate` 1.0.3, and `string.prototype.codepointat` 0.2.1; each uses MIT. Their complete copyright and license notices are in `app/app/src/main/assets/modules/presentation/vendor/THIRD-PARTY-NOTICES.md`. `@resvg/resvg-wasm` 2.6.2 is declared by PptxGlimpse under MPL-2.0, but verification found it absent from the browser bundle, so its code and WASM are not included. Versions and license identifiers were checked against resolved package metadata and license files; `@xmldom/xmldom` and `rtf.js` were verified from the exact-version npm tarballs referenced in the bundled precompiled chunks, the `@nodable/entities` notice at the package's published gitHead, and `xml-naming` and both opentype.js runtime dependencies in the resolved bundle-build directory.
