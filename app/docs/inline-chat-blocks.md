# Inline chat blocks

Assistant Markdown fences are dispatched by the language-to-renderer registry in `InlineBlockRenderers.kt`. SVG, chart, and LaTeX blocks draw natively in Compose. Ordinary Markdown remains on the existing renderer path, which includes GFM tables and applies horizontal scrolling when a table is wider than its measured message area. Invalid or unfinished final special fences fall back to copyable code.

## Dependencies

- `com.caverock:androidsvg-aar:1.4` - Apache-2.0. AndroidSVG draws sanitized SVG into Compose Canvas. The Maven Central AAR is 202,395 bytes.
- `io.github.huarangmeng:latex-renderer-android:1.5.6-kt2.1.0` - MIT. Compose Latex renders fenced equations with Compose Canvas and includes KaTeX 0.16.11 TTF fonts, also MIT. The renderer, parser, and base Android AARs total 1,668,515 bytes. Its documented compatibility variants do not exactly match this app's Kotlin 2.2.20 and Compose UI 1.9.0 toolchain; this selected artifact variant declares Kotlin 2.2.10 and Compose UI 1.9.3 and must remain covered by app builds.

The LaTeX integration accepts fenced `latex` and `tex` source. It validates parser diagnostics before rendering, wraps long equations to the message width, follows Material 3 colors, and enables the library's MathSpeak TalkBack description. `$$...$$` remains on the Markdown renderer path because that renderer does not provide LaTeX math support.

Before publishing, include AndroidSVG 1.4 (Apache-2.0), Compose Latex 1.5.6-kt2.1.0 (MIT), and its KaTeX font notices (MIT) in the app's Open-source licenses section.
