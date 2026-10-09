# Original Jarvys mascot: final v2 source and host proof

Small, source-only backup for the original Jarvys asset. This package contains no
compiled programs, vendored dependencies, preview images, caches, or test logs.
The Python and JavaScript files are source files with non-executable permissions.

## Source and provenance

- `author_jarvys.py` is the unchanged deterministic, offline, standard-library
  authoring script. Running it regenerates `jarvys-main.scene.json` and a local
  author-validation report; it does not compile Rive or render images.
- `jarvys-main.scene.json` is the original final v2 scene. It matches the production
  source at repository-relative `app/app/src/main/assets/bot_mascots/jarvys/source.json`.
- The production binary is at `app/app/src/main/assets/bot_mascots/jarvys/asset.riv`.
  It was produced by the project's existing
  `app/app/src/main/java/com/jarvys/agent/BotMascotSceneCompiler.kt`, using
  `BotMascotSceneCompiler.compileProduct`. No substitute CLI-generated asset is
  used by this proof.
- Geometry and motion were authored for Jarvys after inspecting the project's
  `app/artwork/jarvys-app-icon-source.png`. The launcher reference was unchanged.
  No third-party mascot geometry, motion tracks, or scene template was copied.
  Reusable project proof code was adapted for this single Jarvys source.
- Runtime checks use the official `@rive-app/canvas-advanced` **2.44.1** package
  (MIT), maintained by [Rive](https://rive.app); its package metadata identifies
  [rive-wasm](https://github.com/rive-app/rive-wasm/tree/master/js) as the source.
  The dependency is not included here. Host pixel captures used `@napi-rs/canvas`
  **1.0.10** as a Canvas2D adapter; that adapter is also not included.

The bounded writer follows the [documented Rive format](https://rive.app/docs/runtimes/advanced-topic/format) and the public [MIT runtime sources](https://github.com/rive-app/rive-runtime). Runtime licensing does not grant rights to the proprietary editor or CLI; neither is embedded or invoked by this route.

## Final v2 identity

SHA-256 values:

| Item | SHA-256 |
| --- | --- |
| Authoring source | `db197ca722009680280bab3c9192dc44f6fac9688bec9c6bc969cbed3dac648a` |
| Scene JSON, 32,705 bytes | `e9f4d597db5b5db04732357ea1e64f228b20602a4c12c42021a73b0522a57e2f` |
| Compiled asset, 13,720 bytes | `1b8b702bbe38156e690d442cbed82a35d939e25fec0e91804b261199e9b1ae68` |
| Compiler source used | `4ea38ee0c969aca3137023b95fdc167aa7df75ac61dd4386960a43976f51fc02` |
| Unchanged launcher reference | `5e0f5ae21b58bb4fbdea80abc80af69cea3095354374293e5f1b06fe969558a4` |

The delivered previews are not included in this source package. Their final hashes
are recorded solely to identify the reviewed v2 exports:

- Actual-size PNG: `a3a2e5f8315bae103b7f060dad7f595a4c475136e38d14847ea5ff16dbc37e17`
- Motion GIF: `32f77311deb7c6fb6cec672ff35cca2b181d9d61a01632474eed27c0020999b9`
  (60 frames, 60/70 ms delays, exactly 4,000 ms total).

## Measured host evidence

The following applies to the exact final v2 source and binary above:

- 61 nodes, 47 shapes, hierarchy depth 4; 18 timelines with the same 24 reset
  targets each; 432 property tracks, 521 keyframes, 1,637 binary records.
- Independent decoding exactly matches all source geometry, timeline keys and
  the fixed `MascotState` / `MascotController` contract. All 17 intentionally
  malformed variants are rejected by the exact-structure checker. This measures
  checker rejection, not safe runtime handling of malformed input or a security
  audit.
- Official WASM: 306 directed transitions among 18 states (including 72 between
  normal states), 89 authored interpolation midpoints, independent instances,
  motion in all 9 normal states, and static behavior in all 9 Reduced states.
- Fresh default instance enters Idle; each Reduced pose is applied on the first
  frame and stays static for 180 frames. No legacy state-machine inputs are used.
- 36 host captures support 18 pixel-pair checks: all 9 normal pairs change and all
  9 Reduced pairs are pixel-identical; the 9 Reduced poses are distinct.
- 90 direct actual-size renders cover all 18 states at 16, 22, 30, 34 and 48 px.
  Motion review used 240 frames across Working, Done, Error and Interrupted.
- Independent bounds sampling covers 7,398 quarter-frame poses. All geometry
  remains inside the 256 x 256 artboard, with minimum conservative margin
  8.361075 source pixels (Error, frame 48, left sole). Ellipse extents are exact;
  rounded rectangles use conservative transformed corners. This is sampled
  analytic evidence, not a continuous-time proof or device pixel measurement.
- Padded official-WASM renders at the closest Error pose show no pixels outside
  the artboard at 22, 30, 34, 48 or 256 px. The last artboard row is clear at
  30/34/48/256 px; at 22 px, maximum alpha on that row is 37/255.

Visual review accepted the Jarvys identity at 22/30/34 px and the bounded larger
motions: Done waves once, Error tilts and settles, Interrupted raises a straight
pod. Keep explicit accessible status labels; tiny animations do not reliably
communicate semantic status on their own.

**This is host evidence only. It does not establish Android device playback,
Android renderer correctness, app integration behavior, or full-suite acceptance.**
Those must be verified separately against the final application build.

## Reusable checks

The checks read source and existing compiled bytes and print JSON to stdout.
They do not modify the app, invoke Gradle, install dependencies, or compile a Rive
asset. Python checks need Python 3 and its standard library. WASM checks need Node
and a separately installed official runtime version 2.44.1.

From this directory, set `ASSET_RIV` to the existing production `asset.riv` and
`RIVE_WASM_DIR` to the directory containing `package.json`, `canvas_advanced.mjs`
and `rive.wasm` from that installed runtime, then run:

```sh
python3 check_structure.py "$ASSET_RIV"
python3 check_bounds.py
node validate_runtime.mjs jarvys-main.scene.json "$ASSET_RIV"
node validate_initialization.mjs jarvys-main.scene.json "$ASSET_RIV"
```

Export `RIVE_WASM_DIR` before the Node commands. An absent source, binary, wrong
runtime version, or mismatched source/binary fails rather than passing an empty
fixture set. `check_structure.py --scene FILE` and `check_bounds.py --scene FILE`
can select an explicitly provided source. The checks target Jarvys alone.

To verify regeneration without changing the bundled source, copy
`author_jarvys.py` to an empty temporary directory, run it there, and compare the
regenerated JSON hash to the value above. This regeneration is deterministic.
