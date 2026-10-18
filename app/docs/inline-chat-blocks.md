# Inline chat blocks

Assistant Markdown fences are dispatched by the language-to-renderer registry in `InlineBlockRenderers.kt`. SVG, chart, and LaTeX blocks draw natively in Compose. Ordinary Markdown remains on the existing renderer path, which includes GFM tables and applies horizontal scrolling when a table is wider than its measured message area. Invalid or unfinished final special fences fall back to copyable code.

## SVG fidelity

The original SVG is not available on this PC: searches of the worktree, local project, captures, logs, and temporary files found no source. The owner reports boxes, labels, and proportions matching Chrome, with filled arrow triangles about 2.4 times larger than Chrome's thin chevrons. The exact report cannot be reproduced without that source.

I rendered generated 160 × 80 SVGs with AndroidSVG 1.4 in Robolectric native graphics (API 34) and Chrome headless. The table reports the alpha bounds of the arrow region and its paint. The shared line/arrow endpoint is included; the `refX=10` and start-marker cases use a region around the marker endpoint. Standard marker bounds and paints matched in both renderers. No tested variant reproduced a 2.4× size difference.

| Variant | AndroidSVG raw → chat renderer | Chrome headless | Divergence and cause |
| --- | --- | --- | --- |
| `markerUnits=strokeWidth`, 10 × 10 `viewBox`, `refX=0`, `refY=5`, `orient=auto`, filled `currentColor`, stroke 1.5 | 15 × 15 px at (80, 33), `#2468AC` → same | 15 × 15 px, `#2468AC` | No size or paint divergence; marker geometry scales with the referring stroke. |
| `markerUnits=userSpaceOnUse`, stroke 1.5 | 10 × 10 px at (80, 35), `#2468AC` → same | 10 × 10 px, `#2468AC` | No divergence; user-space size ignores the stroke width. |
| `viewBox` omitted, dimensions present | 15 × 15 px, `#2468AC` → same | 15 × 15 px, `#2468AC` | No divergence in this sample. |
| `markerWidth/Height` omitted, with and without `viewBox` | 5 × 4 px, `#2468AC` → same | 5 × 4 px, `#2468AC` | No divergence; marker dimensions default to 3 × 3 and scale with `strokeWidth`. |
| `refX/refY` omitted | 15 × 15 px at (80, 40), `#2468AC` → same | Same | No divergence; the renderer applies the default reference point. |
| `refX=10`, `refY=5` | 15 × 15 px at (65, 33), `#2468AC` → same | Same | No divergence; the reference point changes placement, not dimensions. |
| Filled path with `fill=currentColor` | 15 × 15 px, `#202020` → same | Same | No divergence; explicit `currentColor` follows the SVG `color`. |
| Path with omitted `fill`, `color=#F0F0F0`, target stroke `#D02030` | Raw 15 × 15 px black → chat 15 × 15 px `#D02030` | 15 × 15 px black | Raw AndroidSVG and Chrome use SVG's default black fill. The chat renderer sets the marker's inherited fill from the referring stroke so it remains visible in dark theme. |
| `fill=none; stroke=currentColor; stroke-width=1`, via inline, marker-path CSS, and class CSS | 15 × 15 px outline, `#2468AC` → same | Same bounds and paint | No divergence; AndroidSVG resolves the marker's inherited and CSS styles. |
| `fill=context-stroke` | Raw 15 × 15 px black → chat 15 × 15 px `#D02030` | 15 × 15 px `#D02030` | AndroidSVG 1.4 ignores the unsupported paint token and leaves default fill; the chat renderer resolves it from the referencer. |
| `fill=none; stroke=context-fill; stroke-width=1` | Raw marker empty → chat 15 × 15 px outline `#A030D0` | 15 × 15 px outline `#A030D0` | AndroidSVG 1.4 ignores the unsupported paint token; the chat renderer resolves it from the referencer. |
| Marker on `line`, `path`, and `polyline` | 15 × 15 px each → same | 15 × 15 px each | No divergence for these referencing elements. |
| Stroke width 1 / 1.5 / 2 with `markerUnits=strokeWidth` | 10 × 10 / 15 × 15 / 20 × 20 px → same | Same | No divergence; size scales linearly with the referring stroke. |
| Light/dark `color` with explicit `fill=currentColor` | 15 × 15 px, `#202020` / `#F0F0F0` → same | Same colors and bounds | No divergence; explicit `currentColor` follows `color`. |
| `orient=auto-start-reverse` on `marker-end` | Visible code fallback | 15 × 15 px at (80, 33) | AndroidSVG 1.4 rejects this orientation token; Chrome supports it. |
| `orient=auto` / `auto-start-reverse` on `marker-start` | `auto`: 16 × 15 px at (20, 33); `auto-start-reverse`: visible code fallback | `auto`: 16 × 15 px; reverse: 15 × 15 px at (5, 33) | AndroidSVG 1.4 does not support `auto-start-reverse`; `auto` renders in both. |

These measurements verify AndroidSVG's `strokeWidth` scaling, 3 × 3 default marker dimensions, viewBox transform, `refX/refY`, `orient=auto`, CSS marker styles, and line/path/polyline use against Chrome for the samples. Bytecode inspection confirms `renderMarker` uses the referring stroke width unless units are user-space, defaults dimensions to 3, applies reference points and the viewBox, and calculates the tangent for `orient=auto`; the parser accepts `auto` or a numeric angle. Its paint parser supports `none`, `currentColor`, concrete colors, and paint URLs, but not the `context-*` keywords. These findings do not explain the reported 2.4× size. AndroidSVG 1.4 also renders an omitted marker fill black in a light or dark theme. The renderer now clones a marker once per referring element when a contextual paint or implicit fill needs resolution, then sanitizes the serialized SVG again. It preserves geometry and positioning. Unsupported `auto-start-reverse` uses the existing copyable-code fallback.

For predictable inline arrows, author a `viewBox`, explicit `markerWidth`, `markerHeight`, `refX`, and `refY`; use `markerUnits=strokeWidth` for size tied to the line or `userSpaceOnUse` for fixed size. Use `orient=auto`. Use `fill=currentColor` for filled heads or `fill=none` and `stroke=currentColor` for outlines; avoid `context-stroke`, `context-fill`, and `auto-start-reverse` in this renderer. Keep labels legible on portrait phones and sized to fit their shapes.
