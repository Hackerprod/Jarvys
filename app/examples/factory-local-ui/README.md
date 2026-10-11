# Factory local UI example

Copy this directory into an authorized Coding project. Inspect Factory, then build
factory.json to a new output path using the current scope version. Preview the exact
returned APK/hash. Signing/installing remains a separate authorized native flow.

The example needs no capabilities. Entries are session-only DOM state, limited to 20;
closing/reloading clears them. It does not claim durable save. Both runtime-owned CSS
links must resolve from the generated APK; opening index.html as a file is not equivalent.
No bundled CSS needs copying into webDir. Customize appId/name/icon before a real app.

Check at narrow/wide widths, keyboard focus, disabled/empty/list/20-item states, repeated
submits/clear, long text, dark theme and enlarged text. This source is an example, not
proof of Android WebView rendering or physical-device compatibility.
