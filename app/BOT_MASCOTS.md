# Local mascot authoring (UX40, in progress)

**Delivered v57 debug pilot:** the first choreography (v2) was rejected. The broader, several-second v3 candidate is integrated and its resource/UI/lint/package host gates pass. Physical Android acceptance remains open and playback requires explicit experimental activation.

The Full APK send was accepted on 2026-10-09 at 16:54:40 UTC: version 57 / `1.2.50-UX40-pilot`, 31,575,466 bytes, SHA-256 `5875f7170b0f2a7fbde47567e05b5ad8a94dd80a32e8c1805b5a2968b574738c`. It uses the same user-approved D7 debug certificate as v56; all 259 existing ZIP entries match the validated unsigned APK. Play remains unsigned. Sending is not installation, native playback or design acceptance.

The native writer and agent tools compile and save original vector scenes locally. This checkpoint integrates an **experimental, initially disabled Android playback path** and an original Jarvys mascot in the existing account avatar and chief/captain identity. Physical Android playback is **not yet validated**. Custom-bot PNG/glyph fallback and the two-column catalog remain available. `LOCAL_COMPILED` is compilation/integrity evidence, not rendering acceptance, offline model inference, or completed UX40.

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

## Original Jarvys and experimental playback

The bundled original robot preserves the white/silver body, cyan eyes and dark visor of the existing launcher artwork. The launcher and notification icon are unchanged. Its editable JSON contains 61 nodes and 872 keys; the Kotlin compiler generates 18,237 bytes. The bundled source and binary are SHA-256 pinned, bounded and regenerated by the same compiler before native loading. The shared static fallback is a transparent render of its actual reduced idle pose, not a different character.

Jarvys uses the existing 30 dp account avatar and existing chief/captain surfaces. Chief/captain surfaces share the static identity. Live animation is available only through the explicit experimental account-menu activation: tap the top-bar avatar, choose “Animación experimental de Jarvys”, then “Activar para esta sesión de la app”. Consent stays only in process memory across drawer, chat and background changes; the native subtree is removed while ineligible and recreated with current activity on return. Stop, a current playback failure or a new process clears consent. No saved setting or automatic first JNI load is used. Current typed tool invocations can select Working; explicit current-run terminal outcomes select Done, Error or Interrupted. Historical rows, uncertain activity, model-only inference and unknown states stay static. Approval/user-decision/compaction paths suppress the current activity marker. Visual test controls are labeled as tests and never assert real agent activity.

The account menu also provides “Prueba visual de Jarvys” / “Jarvys visual test”. Custom bots with a verified local package have a separate visual test entry in the editor. Each test begins static and requires Start. It provides two independent instances, nine modes, reduced-motion controls and fixed-field diagnostics without conversations, bot names, source, private paths or stack traces. Requested input, native readback and surface availability are distinguished; those signals do not prove correct pixels. A 15-second readback/frame watchdog falls back safely where errors are recoverable. A native process crash cannot be caught by Kotlin fallback.

The shared runtime initializer executes once per process, only after consent and canonical byte verification. Rive Android 11.14.1 is pinned. Its transitive Compose BOM alone is excluded, preserving the app's Compose BOM 2025.08.00, Compose 1.9.0 and Material3 1.3.2. Supporting lifecycle/core/startup dependencies follow the resolved graph; the APK includes required third-party notices. Both merged manifests retain other Startup entries and contain no Rive initializer. Runtime workers and native instances are composed only while the corresponding experimental surface is eligible; background/offscreen/covered views do not retain an active rendering loop. Reduced motion uses the dedicated constant poses.

## Host evidence and device acceptance

Independent official Canvas2D/WASM 2.44.1 accepted the final Kotlin-produced Jarvys binary. It passed 306 directed transitions, 314 linear midpoint checks, fresh initial/reduced-pose checks, separate instance checks, 2,864 normal-frame renders across four sizes. Independent decoding matched all 2,114 binary records to the source and rejected 17 controlled mutations. The 17 cases are a checker test, not runtime fuzzing. Geometry was sampled at 8,646 poses; minimum measured margin was 14.03 source pixels. Real-size host renders at 22–34 pixels preserve the visor/eyes, but accessible status text remains necessary. Host/WASM evidence is not Android rendering or performance evidence.

Device acceptance must exercise first import and native binding, actual visible motion, all nine reduced poses, two independent instances, replacement/cancellation while loading, background/foreground and navigation, compact/large-font layouts and system reduced motion. The manual test offers a bounded way to return diagnostics without private conversation data. No emulator or physical-device test is claimed from host/Robolectric tests.

The code snapshot with v2 completed 2,495 Full, 2,113 Play and 64+64 runtime tests. The revised resource and test-only offset changes pass 200 fresh focused/UI tests in each app flavor, with no failures/errors/skips. Full/Play/runtime lint has no added diagnostics against the exact v56 source using the same offline dependency metadata. Final unsigned APKs measure 31,544,512 bytes (Full) and 30,060,629 bytes (Play), respectively +11,478,535 and +11,486,171 bytes against aligned unsigned v56. All four Rive ABIs are present; this is file-size evidence, not a memory/battery benchmark. Physical acceptance remains open. Measure memory, first frame, scrolling and power-related behavior on an actual device; host file sizes are not performance benchmarks. Default activation and full dynamic custom-bot card rollout remain gated. No renderer gate is satisfied by a file header or WASM success. UX40 remains open until its required acceptance evidence exists. UX41/UX42 and UX34 phase 0 retain their separate scope.

The v3 action loops last 4–5 seconds (Thinking 4.8); terminal actions last 3–3.5 seconds and then settle without looping. Some gestures remain semantically ambiguous at small size, especially among waiting modes. Do not present pixel motion or distinct source tracks as proof that users can infer nine labels without text.

## Phone check for this experimental APK

1. Open an existing chat. Tap Jarvys's top-bar avatar → **Animación experimental de Jarvys** → **Activar para esta sesión de la app**. The choice is process-memory only. It stays enabled across chat/drawer/background changes, but rendering pauses while hidden; Stop, a playback failure or a new process disables it.
2. Observe a normal authorized tool operation. Working is shown only for a verified current tool call. Model-only/unknown activity stays static. Explicit final outcomes can show Done, Error or Interrupted. The robot alone is not a reliable status label at small size.
3. For a controlled check, open the avatar menu → **Prueba visual de Jarvys** → **Iniciar prueba**. The test starts with **Reducir movimiento** selected. Turn that test switch off only if motion is appropriate for you; the Android reduced-motion setting always takes priority. Select different modes for A and B and verify they stay independent. Check normal and reduced poses using **He comprobado este aspecto**. These controls represent a visual test, not real agent work.
4. Leave the app, return, switch chats and open/close the drawer. Main-avatar consent should remain in this process, with only current visible activity rendered. The separate visual test intentionally requires **Iniciar prueba** again after losing visibility. A new process should start static.
5. Use **Copiar diagnóstico seguro** in the visual test and share that text with the result you saw. It contains fixed runtime/ABI/API, requested/native input values and manual check flags; no conversation, bot name, source, file path or exception text. “Píxeles disponibles” only reports surface availability, not correct drawing. If the app closes unexpectedly, report which action preceded it; restart should leave playback off.

Physical acceptance, memory/power measurements and default-enabled rollout remain open. Do not remove data or reinstall merely to repeat this test.
