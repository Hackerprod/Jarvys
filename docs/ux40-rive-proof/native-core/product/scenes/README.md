# Original UX40 mascot scene fixtures

Two agent-authored characters for the bounded `bot-mascot-v1` writer contract.
These are data fixtures, not Rive binaries or claims of runtime compatibility.
The authoring script requires no external assets, fonts, network, Rive CLI or commercial editor.

## Files and regeneration

- `nimbo.json`: an asymmetrical floating cloud with three independent dew drops.
- `folio.json`: a hardbound journal with a hinged page wing, ribbon tab, and feet.
- `author_scenes.py`: a deterministic, standard-library-only offline authoring
  script. It writes the two JSON files beside itself and checks their structure.

Run `python author_scenes.py` from any directory. The script does not compile or
render Rive, call a service, install packages, or invoke external executables.
Its shared helpers only construct bounded nodes, reset-complete animation tracks,
and a small semantic status-symbol alphabet. The two character geometries and
movement sequences are authored independently, not generated from a body template
or made by swapping colors.

## Geometry and reading order

Nimbo uses four overlapping cloud lobes with a solid rim, a detached circular
status bubble, and a freely arranged three-drop constellation. There are no legs
or book-like panels. Its motion vocabulary is buoyancy, rearrangement, listening,
and left-to-right relay.

Folio uses nested rounded covers, an offset exposed page edge, a hinged page wing
with printed rules, a ribbon status tab, and two separate feet. It stays grounded;
its motion vocabulary is page turning, opening, tucking, bowing, and tab gestures.

Both use solid opaque ARGB colors. Supported node kinds are `group`, `ellipse`,
and `rectangle` with a positive `radius` for rounded rectangles, matching the
existing writer's field spelling. Geometry is ordered foreground-first, consistent
with the reference fixtures. Independent renderer evidence is documented in the parent README.

The small shared status symbols make the state readable without relying only on
color or motion: resting dot, thinking dots, work bars, queue stack, provider link,
user speech bubble, completion check, error exclamation, and interruption pause.
Exactly one is visible in each state. The character pose remains independently
distinct in every reduced-motion state even when the symbol tracks are excluded.

## Mode and animation contract

One 256 × 256 artboard is expected from the compiler. JSON has exactly the root
fields `contract`, `name`, `nodes`, and `animations`; `contract` is
`bot-mascot-v1`. `name` is character metadata, not an artboard/state-machine rename.

| Mode | Regular animation | Nimbo choreography | Folio choreography |
| --- | --- | --- | --- |
| 0 | Idle | Slow buoyancy with widely spaced drops | Subtle tab and page-corner sway |
| 1 | Thinking | Up-left gaze, tilted cloud, ascending drop constellation | Canted book and tab with a tucked page corner |
| 2 | Working | Three drops relay sequentially from left to right | Hinged page turns by changing width and angle |
| 3 | Queued | Compact body with a straight row of patient drops | Closed page wing and feet together |
| 4 | WaitingProvider | Rightward gaze with drops leaning toward an external source | Rightward gaze and an open listening page |
| 5 | WaitingUser | Upright open face and one inviting raised drop | Raised ribbon and outward-facing page invitation |
| 6 | Done | A single rise settling into an open drop fan | One gentle bow settling with the page open |
| 7 | Error | One asymmetric droop, narrow mouth, lower clustered drops | Folded-in page and drooping ribbon, one settling motion |
| 8 | Interrupted | Brief settling into a tidy pause arrangement | Page held halfway, upright book, parallel feet |

Each row also has exactly one animation named `<RegularName>Reduced`.
All reduced animations have duration 1, `loop: false`, and one frame-zero key per
track. They show a deliberately authored state pose rather than a random frozen
frame. They contain no continuing motion. The normal Done, Error, and Interrupted
animations are finite; the other normal animations loop with matching endpoints.
Keyframes are linear under the writer contract, with no easing, opacity animation,
flashing, spinning, or rapid full-character shaking.

## Reset safety and validation scope

Within each scene, all 18 animations have exactly the same node/property target
set. Every track explicitly starts at frame zero, including all hidden status
symbols and properties a state otherwise leaves unchanged. A state switch cannot
intentionally inherit a prior state's transform. Only `x`, `y`, `rotation`,
`scaleX`, and `scaleY` are animated.

The script asserts:

- Exact root contract and 18 animation names; nine mode names in the required order
- Node naming, field sets, parent-before-child ordering, and bounded hierarchy
- Supported geometry and transform fields, finite numeric values, opaque ARGB
- Dimensions, radius, source byte budget, node/track/key/duration limits
- No duplicate targets, full cross-state target equality, explicit frame-zero keys
- Strictly increasing in-range frame numbers and loop endpoint equality
- Constant non-looping reduced tracks, one visible state symbol, and nine distinct
  reduced character poses even with the status symbols excluded
- Nine distinct normal motion definitions, each with at least one moving target
- Different geometry/hierarchy between the two characters, excluding signal labels
- Exact file readback after deterministic generation

These checks do **not** test Rive binary encoding, controller transition selection,
rendered draw order, silhouettes at small sizes, clipping on interpolation,
accessibility preferences in the app, or Android integration. Separate compiler/runtime evidence and remaining device gates are documented in the parent README.

## Structural authoring results

| Fixture | Nodes | Shapes | Depth | Tracks/state | Total tracks | Total keys | Source bytes |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Nimbo | 59 | 44 | 3 | 32 | 576 | 621 | 115,962 |
| Folio | 60 | 45 | 4 | 31 | 558 | 589 | 113,153 |

Both are below 128 KiB, 96 nodes, depth 8, 64 tracks/state, 64 keys/track,
and 600 frames/animation. The largest actual duration is 216 frames at the
writer's expected 60 fps. File hashes are printed by regeneration so changes can
be compared without maintaining a stale hash manifest.
