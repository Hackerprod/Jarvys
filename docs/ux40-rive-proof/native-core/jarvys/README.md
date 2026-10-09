# Original Jarvys mascot: v3 visible gestures

V3 replaces the former micro-motion choreography with distinct, several-second
hand/body actions while preserving Jarvys's original robot identity. Host visual review found the revised actions materially clearer after separating
the user invitation from Idle and removing Error's shared hand-to-temple gesture.
An additional blind reading still found some gestures semantically ambiguous at small sizes. That does not make their tracks identical, but it limits any claim that users can recognize nine states without labels. Text remains the primary status signal. This is **host authoring/render evidence, not Android or APK acceptance**.

This source-only package includes no compiled executables, vendored dependencies,
preview files, caches or generated reports. Scripts have non-executable file
permissions. No proprietary editor or CLI is embedded or invoked.

## Source and history

- `author_jarvys.py` deterministically authors `jarvys-main.scene.json` using only
  Python's standard library. It also emits a local author-validation report.
- The production scene path is
  `app/app/src/main/assets/bot_mascots/jarvys/source.json`; the compiled binary is
  `app/app/src/main/assets/bot_mascots/jarvys/asset.riv`.
- The existing writer is
  `app/app/src/main/java/com/jarvys/agent/BotMascotSceneCompiler.kt`, invoked through
  `BotMascotSceneCompiler.compileProduct`. This proof does not substitute a fake
  `.riv`, alternative renderer or renamed scene template.
- Geometry was authored after inspecting `app/artwork/jarvys-app-icon-source.png`.
  The launcher is unchanged. The silver oval shell, broad cyan-rimmed visor, two
  cyan eyes, twin antennae, side pods, two feet and cyan chest slot are preserved.
- The earlier v2 source and evidence remain available at the
  [historical v2 proof checkpoint](https://github.com/Hackerprod/Jarvys/tree/3aeedb468c7bebfcbc888ee2d62cdf0dd33f1bc5/docs/ux40-rive-proof/native-core/jarvys).
  V2 was rejected for similar and perceptibly brief movement; it is historical
  provenance, not the accepted choreography for this revision.

The official runtime used for host proof is `@rive-app/canvas-advanced` **2.44.1**
(MIT), with `@napi-rs/canvas` **1.0.10** as the host Canvas2D adapter. They are not
included here. The bounded writer follows the
[Rive format](https://rive.app/docs/runtimes/advanced-topic/format) and public
[MIT runtime source](https://github.com/rive-app/rive-runtime).

## Frozen v3 identity

- Scene JSON: **41,445 bytes**, SHA-256
  `983546e91329e6ab22e1191fbfb0f3c240384c8eabbacc62a1d96951e39e236b`
- Real compiled Rive: **18,237 bytes**, SHA-256
  `514c6cfb16008eb6ecfd13c136ab6032c2ebd615c11f2325491fa762fc73981a`
- Compiler source used: SHA-256
  `4ea38ee0c969aca3137023b95fdc167aa7df75ac61dd4386960a43976f51fc02`
- 61 nodes, 47 shapes, depth 4; 18 timelines, 31 shared reset targets per timeline,
  558 property tracks and 872 keyframes.

Only groups, ellipses, rounded rectangles, solid fills and supported transform
tracks are used. There is no new app/runtime dependency or visual primitive.
The two extra pod scale tracks support the invitation's forward perspective and
reset to 1 in all other states.

## Distinct choreography and true timing

| State | Timing | Action |
| --- | --- | --- |
| Idle | 5.0 s loop | Relaxed sway, blink, hands down |
| Thinking | 4.8 s loop | Pod to temple, inward tilt and consideration, lower hand |
| Working | 4.0 s loop | Sustained alternating inward/downward work strokes |
| Queued | 4.0 s loop | Gathered hands, patient weight shift and toe taps |
| WaitingProvider | 4.0 s loop | Listen high on the right, scan/reach outward, antenna response |
| WaitingUser | 4.0 s loop | One enlarged forward pod offers and beckons; other pod stays low |
| Done | 3.5 s one-shot | Two-hand celebration arcs, then satisfied open rest |
| Error | 3.5 s one-shot | Recoil, inspect with both hands, open and recover |
| Interrupted | 3.0 s one-shot | Decelerate a work motion into a straight raised stop pod |

Done's main arm/body action continues through 3.3 s, Error's through 3.2 s, and
Interrupted's through 2.6 s. These durations are not obtained by padding a
sub-second action with a long frozen tail. Done, Error and Interrupted do not
loop, and their final poses differ from one another and from Idle.

The invitation stays below the eye area, unlike Done's raised celebration. Error
has no contemplative temple gesture. Reduced variants are nine intentional,
instant, distinct static poses. There is no private task text, flashing, crying,
punitive expression or audio. Keep accessible state labels; small animations
alone do not guarantee semantic recognition.

## Reusable checks

Python source/structure/bounds checks require Python 3. Runtime checks require
Node and the separately installed official WASM package. The individual-image
checks additionally require Pillow and NumPy. Preview labels require the DejaVu
Sans TrueType font, discoverable by Pillow as `DejaVuSans.ttf`, or an explicit
`JARVYS_PROOF_FONT` path to that font. No check installs dependencies,
changes the app, invokes Gradle or compiles a replacement asset.

From this directory, set `ASSET_RIV` to the existing compiled production asset,
and export `RIVE_WASM_DIR` pointing to the installed runtime directory containing
`package.json`, `canvas_advanced.mjs` and `rive.wasm`:

```sh
python3 test_jarvys_scene.py
python3 check_structure.py "$ASSET_RIV"
python3 check_bounds.py
node validate_runtime.mjs jarvys-main.scene.json "$ASSET_RIV"
node validate_initialization.mjs jarvys-main.scene.json "$ASSET_RIV"
```

`test_jarvys_scene.py` accepts `JARVYS_SCENE_SOURCE`; structural/bounds checks
accept `--scene FILE`; the JavaScript checks accept explicit source/binary paths.
Checks fail for missing inputs, wrong runtime version, wrong source identity or
mismatched actual binary. Six authoring regression tests cover exact source,
budgets, resets, true timing, broad actions, and static/terminal pose differences.
The exact-structure checker also exercises malformed-binary mutations; rejection
by this checker is not a security audit or a claim of safe runtime rejection.

For deterministic regeneration without modifying checked-in files, copy
`author_jarvys.py` into an empty temporary directory, run it there, and compare the
regenerated JSON SHA-256 to the frozen value above.

## Individual actual-size preview reproduction

Set these environment variables explicitly:

- `JARVYS_SCENE_SOURCE`: scene JSON to inspect
- `JARVYS_RIV_FILE`: existing compiled Rive binary
- `JARVYS_PROOF_OUTPUT`: a **new, empty** writable output directory outside the
  checkout; do not mix capture batches or overwrite reviewed exports
- `RIVE_WASM_DIR`: installed official WASM runtime package
- `CANVAS_DIR`: installed `@napi-rs/canvas` package
- `JARVYS_PROOF_FONT` (optional): DejaVu Sans TrueType font file; defaults to
  `DejaVuSans.ttf` through Pillow's font discovery

Then run:

```sh
node render_individual.mjs
python3 assemble_individual.py
python3 check_runtime_pixels.py
python3 test_runtime_pixels.py
```

The renderer directly produces 256 px and actual 22/30/34 px images. Robot pixels
are never resized. Each independent GIF samples the runtime every 3/60 s and
encodes exactly 50 ms per frame: 20 fps with checked real duration. One-shot GIFs
do not loop and have no added rest frames. Opaque clip names allow a first
label-free review; the generated manifest identifies each state. Separate ordered
phase sheets, static Reduced images and final-pose images supplement the clips.

The renderer checks the exact frozen source and Rive SHA-256 values before
loading the asset and records both in its report. The pixel checker requires
both matching hashes, so a stale compatible binary cannot be silently paired
with current source metadata.

The pixel checker rejects incomplete evidence before evaluating pixels: exactly
nine unique mode/state entries, four sizes, frozen-source frame counts, fps,
durations and loop flags, a complete exact filename inventory, and correctly
sized visible RGBA PNGs are required. Empty reports, missing states/frames, empty
frame directories, extra frames and malformed images fail. The harness tests
include the complete positive fixture and isolated negative mutations; they
never edit the input fixture.

Endpoint stability is measured from an explicit duration + one 60 Hz tick settled
sample, then requires **exact pixel equality** three seconds later. The extra tick
clamps float32 endpoint interpolation; it does not weaken the equality check.

## Measured host results for the frozen candidate

- Official WASM: 306 directed state transitions; 314 interpolation midpoint
  checks; independent instances; no self-reentry; static Reduced states applied
  correctly on their first frame.
- Every normal frame rendered at 22/30/34/256 px has a clear outer pixel border.
- Reduced scenes are exactly unchanged after four seconds; all three terminal
  states are exactly unchanged three seconds after the settled endpoint.
- At each checked size, all nine Reduced images are pixel-distinct; Done, Error
  and Interrupted final images are distinct from each other and Idle.
- Actual 22 px salient rendered-feature spans are approximately: Thinking 4.92,
  Working 3.61, Queued antenna 3.16, WaitingProvider 3.91, WaitingUser 4.27,
  Done 6.80, Error 5.32 and Interrupted 4.03 px. Idle remains gentle at 1.41 px.
  These image-based measurements support visible movement, not unlabeled-status
  recognition or Android playback claims.

Host checks do not establish device performance, platform rendering, battery
behavior, application state mapping or APK acceptance. Verify those separately
against the actual final build.
