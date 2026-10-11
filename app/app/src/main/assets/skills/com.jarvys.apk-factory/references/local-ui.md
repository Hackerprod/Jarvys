# Factory guidance factory-guidance-v81 / local-ui

Pure.css 3.0.0 is bundled in every new generated APK and exact Factory preview. It is CSS,
not a native capability or JS API. Inspect `localUiLibraries` for paths, bytes, licenses and
SHA-256. SDK2/schema1, 20 capabilities/42 methods remain unchanged.

In HTML link /factory-ui/pure-3.0.0/pure-min.css, then
/factory-ui/pure-3.0.0/grids-responsive-min.css, then your own local stylesheet.
Use pure-form / pure-form-stacked for labeled forms, pure-button / pure-button-primary
for ordinary buttons, pure-table for tables and pure-g with pure-u-1 / pure-u-md-1-2
for responsive columns. Include viewport meta; use semantic labels and visible focus.
Use local separate JS event listeners, never inline handlers, eval, CDN or imports.

The fixed-inclusion policy retains both CSS files plus LICENSE.txt, normalize-LICENSE.txt
and PROVENANCE.json under that versioned runtime path, even when no page links them.
These five resources have pinned exact bytes and cannot be project replacements. Do not
copy them into webDir or claim they are implicitly provided by a separate installed Jarvys.
Project limits remain 128 files / 1 MiB each / 8 MiB total; library overhead is separate,
at most 32 KiB per file and 64 KiB total. No compiler/download occurs per application.

Licenses: Pure BSD-3-Clause, embedded normalize MIT; notices are included in each APK.
Project styles own themes, contrast and touch targets. Preserve the single native owner
of safe-area/IME insets. CSS does not implement custom-widget keyboard behavior, native
APIs, persistence or automatic dark mode. Responsive/focus/contrast/font-scale checks
still need actual rendering. Desktop browser results do not establish Android WebView,
OEM or device compatibility. Keep all existing physical acceptance gates open.
