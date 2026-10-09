# UX40: bounded local .riv writer proof

9 October 2026. **The central serialization hypothesis passed on a host.**
An original Python-standard-library writer converts two authored JSON scenes to
real `.riv` files, without invoking Rive CLI or contacting Rive's service. The
official Rive WASM runtime imports, animates and renders both outputs.

This is a candidate foundation for on-phone compilation, not an Android port or
an integrated Jarvys feature. Python runs on the host in this proof. No Android
device, APK size, battery, memory or frame-rate result is claimed.

## Original examples and supported subset

- **Miga:** round courier, capsule feet and satchel; breathing at rest, squash,
  jump and opposing foot movement while active.
- **Tallo:** potted sprout with hierarchical stalk and leaf groups; a slow stalk
  sway at rest, independent leaf rotations during activity.

The JSON stores geometry and choreography, rather than selecting a character
template. These two agent-authored examples demonstrate distinct content; they
do not establish autonomous phone-side generation quality or arbitrary Rive
authoring.

Supported: one 256×256 artboard named `Mascot`, groups, filled ellipses and rounded
rectangles, solid ARGB colors, hierarchical transforms, linear float keyframes
for x/y/rotation/scale, and a fixed three-state controller. `scene.name` is source
metadata; the artboard name intentionally remains the stable `Mascot` contract.

Inputs are numeric `mode` (nonpositive Idle; positive Active) and boolean
`reducedMotion` (Reduced overrides mode). This uses runtime state-machine inputs,
not the nine-mode ViewModel/data-binding contract of the earlier CLI proof.
Every timeline explicitly resets every animated property. Reduced is constant.

Excluded: RML parsing, arbitrary state graphs, ViewModels, paths, bones, masks,
gradients, text/fonts, images, audio, scripts, shaders, external URLs and editor
exports. The shared controller is fixed; mascot geometry and movement are not.

## Verified evidence

- 31 schema/serializer tests, including malformed values, references, keyframes,
  budgets and output-version rejection.
- Independent official `@rive-app/canvas-advanced` 2.44.1 import: three timelines,
  one state machine and two correctly named inputs per mascot.
- All six ordered transitions between the three states per mascot; no repeated
  same-state entry; movement after a loop boundary; numeric linear midpoints;
  neutral reset; reduced-mode stillness for all selected animated nodes at every
  sampled frame while mode changes; independent instances sharing file data.
- Four invalid-byte/version inputs rejected by the runtime. This is not fuzzing.
- Twelve actual runtime captures. Two temporal comparisons each for Idle and
  Active change pixels; both Reduced comparisons at frames 1/181 are identical.
- Source and pixel review confirmed distinct designs and recognizable output.

`evidence/host-results.json` records exact file hashes, byte counts and results.
Miga is 1,303 bytes; Tallo is 1,158 bytes. These are asset sizes, not APK or runtime
cost. The native Canvas adapter is only a verification tool on the host.

## Limits and remaining gates

The writer caps 96 authored nodes, depth 8, 64 tracks per state, 64 keys per
track, 600-frame duration, 80-byte names and 64 KiB output. The CLI entry point
additionally bounds JSON input to 128 KiB. A future Android/API ingestion layer
must enforce its own source-byte and work limits before parsing.

Bounds do not guarantee good design or visibility: nested transforms compound,
transparent/group-only content can pass, and this is not a security audit or a
validator for arbitrary untrusted `.riv` files. Production needs richer semantic
validation, adversarial tests and controlled resource budgets.

Next gate: implement the small serializer in a suitable Android-side language
or otherwise select a supported local execution mechanism, then test the exact
bytes and state behavior in the selected Android runtime. Do not assume this
Python host result proves an APK can generate them. Expand the nine-state
contract, generic agent tools, durable generation transaction and lifecycle
handling only after that gate and scope review. Keep PNG fallback.

On-phone compilation and offline AI inference are separate decisions. The
current proof says nothing about running the model itself offline.

## Reproduction

From this directory, with Python 3 and Node available:

```sh
python author_examples.py
python test_writer.py
RIVE_WASM_DIR=/path/to/official/canvas-advanced/package node validate_runtime.mjs
RIVE_WASM_DIR=/path/to/official/canvas-advanced/package \
  CANVAS_DIR=/path/to/napi-rs/canvas node render_node.mjs
python check_pixels.py
```

The last command uses Pillow for pixel comparisons and the labeled contact
sheet. The contact-sheet script currently expects the Linux DejaVu Sans font at
`/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf`. Runtime paths can instead default to matching packages in local
`node_modules`. Tested versions: Rive WASM 2.44.1 and Canvas adapter 1.0.10.
The generator itself needs none of these runtime/rendering packages.

See [SOURCES.md](SOURCES.md) for format references and retained MIT attribution.
No downloaded executable, native library, compiled `.riv`, cache, credentials or
local browser profile belongs in this source backup.
