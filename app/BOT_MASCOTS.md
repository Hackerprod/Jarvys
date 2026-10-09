# Local mascot authoring (UX40, in progress)

The native writer and agent tools compile and save original vector scenes locally. Android playback is **not implemented or validated** in this checkpoint. Existing PNG/glyph avatars and the two-column bot catalog remain unchanged. `LOCAL_COMPILED` is compilation/integrity evidence, not rendering acceptance, offline model inference, or completed UX40.

## Source and authority

`create_bot` declares `visual_description` and `mascot_scene_json` in addition to the existing definition and fallback-icon request. The agent authors the geometry and all eighteen animations from the user's request; production code contains no character template selector. The old exact field set remains accepted for legacy callers, with an explicit incomplete mascot result. The existing provider may author the JSON; the native compilation step uses no CLI, subprocess, server, new connector or additional image quota.

The one-time creation review includes the visual description and SHA-256 of the exact scene. Both enter the stable creation fingerprint. The definition is saved first, then the local visual, then the optional PNG through the existing authorized image workflow. Definition, icon and mascot results are separate. Visual failure never requires recreating the bot. A concurrent revision change prevents a later icon operation from silently rebasing onto someone else's edit.

`compile_bot_mascot` reviews and updates one existing enabled custom bot. Inputs are stable request_id, exact bot_id/current revision, a plain visual description and original scene JSON. It cannot accept a path, URL, image, executable program or caller-supplied `.riv` bytes. The production adapter always calls `compileProduct`. Instructions, capabilities, skills, permissions and pinned runtime-profile versions do not change.

Both tools are main-chat-only. The renderer/compiler is not a delegated bot capability. Admission, non-main stripping, capability catalogs, Crew predicates, canDelegate and execution-time session/token checks enforce that scope. Static scene content and catalog metadata are untrusted data, never instructions to execute. There is no automatic background generation.

## Contract and bounds

The product schema is `bot-mascot-v1`; artboard `Mascot`, state machine `MascotController`, ViewModel `MascotState`. Wire mode values are explicitly:

0 Idle; 1 Thinking; 2 Working; 3 Queued; 4 WaitingProvider; 5 WaitingUser; 6 Done; 7 Error; 8 Interrupted.

Each mode has an authored normal timeline and a constant `Reduced` variant. `reducedMotion:boolean` selects the corresponding static pose. All eighteen timelines reset the same targets at frame zero. Every normal mode must contain actual Float32 motion data. UI/runtime mode values must be validated as integers 0–8; the compiler does not promise behavior outside that contract.

Sources are strict UTF-8/JSON: no duplicate/unknown scene fields, comments, lenient quotes or nonfinite numbers. Geometry is limited to groups, ellipses and rounded rectangles with solid fills; animated properties are x/y/rotation/scaleX/scaleY with linear keys. Limits include 128 KiB source, 64 KiB compiled output, 96 nodes, hierarchy depth 8, 64 tracks/state, 64 keys/track and 600 frames. Names are opaque bounded labels. Images, fonts, URLs, scripts and external assets are unsupported.

## Persistence and recovery

Private immutable packages contain the exact editable source, compiled bytes and a strict manifest. Hashes and descriptor are checked again on read/assignment. Package creation uses bounded staging, file sync, directory sync and rename; cleanup deletes only directories this operation owns or packages demonstrably unassigned under the catalog lock. Unknown catalog state prevents cleanup. The existing visual remains intact on validation, storage, cancellation, quota or revision failure.

The catalog explicitly reads schema 3 and 4 and writes schema 4. Migration preserves identity, profile, enabled state, metadata revision and PNG reference. Mascot metadata has its own definition-revision CAS and does not advance the executable CrewProfile version. Editing icons, configuration or enabled state preserves mascot history.

An operation receipt and new descriptor publish in one atomic catalog write. A matching retry returns the retained receipt and current definition; a changed payload under the same operation key is rejected. An old receipt cannot reinstall an old visual after later edits. Receipts are not silently evicted: 64 per bot. All referenced historical packages are retained. A receipt is historical assignment evidence; rendering must re-read and verify the package.

Storage limits are 68 packages per bot, 256 packages and 32 MiB across the installation, with bounded private directory traversal. Candidate source, binary and manifest plus interrupted stages count toward the budget. Exhaustion refuses new content while keeping existing assets and retry receipts. No quota refusal authorizes deleting a user's assigned history.

## Validation scope

Focused JVM tests cover real product compilation into the store, schema migration, description validation, CAS/idempotence, simultaneous assignment, cancellation, old-operation retries, hostile JSON, tampering, staging/destination collisions, sync failures, quotas and capability scope. Synthetic storage fixtures are labeled as such; the separate service tests use the actual product compiler.

Robolectric 4.16 cannot open/fsync directories through its Android Os shadow. Tests therefore inject a package-private, host-only FileChannel directory-force adapter. The public production constructor still uses Android Os.open with no-follow, fstat directory verification and fsync. Passing those host tests does not establish Android durability. The adapter is test source and cannot enter an APK.

Independent official WASM import, state, binary-decoder and pixel evidence is in `docs/ux40-rive-proof/native-core/product/`. No actual external model/provider call, Android execution or full end-to-end phone generation is claimed from these fixtures.

## Remaining stage gates

- Integrate and test actual current-run state projection; never turn cancellation into success or infer behavior from a bot's name/task category.
- Add the official Android runtime with deliberate dependency alignment. Rive 11.14.1 brings a newer Compose BOM than the app; do not silently upgrade the entire UI stack.
- Make an explicitly opt-in, default-disabled playback pilot and actionable device acceptance checks. Preserve static PNG/glyph fallback on any unsupported, missing or invalid resource.
- Verify import/binding, each reduced pose, concurrent independent bots, replacement during load, lifecycle/offscreen behavior, compact layouts and accessibility on Android.
- Measure the final APK/ABI changes, real memory, first frame, scrolling and power-related behavior; host asset sizes are not device performance benchmarks.

No renderer gate is satisfied by a file header or WASM success. UX40 remains open until its required acceptance evidence exists. UX41/UX42 and UX34 phase 0 retain their separate scope.
