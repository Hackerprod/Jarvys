# Static Jarvys identity v90

Delivered: version 90 / `1.2.83-MASCOT`, source `7b1317475dc5e2805927c6c6068816f28b0f52df`. Prior delivered APKs remain unchanged.

The user-provided RGBA PNG is preserved byte-for-byte as the source artwork and shared static mascot: 1254 × 1254, SHA-256 `2c003833dc3f14883705a3fdcc1e694ebec06e82ea8a15b6c14e0300737fef52`. The existing deterministic Pillow 12.3.0 generator produces the fifteen density-specific launcher/adaptive foreground PNGs. No artwork redraw, cleanup, new mask or background design is introduced. The static Compose renderer uses ContentScale.Fit.

Jarvys animation availability is closed for this identity. Even an old process opt-in or a supported activity mode cannot prepare the old robot renderer. The account-menu animation toggle, warning and visual-test entry are dormant; their underlying controls, consent data model and historical Rive assets remain available for a future explicitly reviewed integration. Navigation, background and route changes do not enable the old renderer. Custom bot mascots and their animation facilities remain unchanged.

The separately requested sad animation is not bundled or integrated by this change. Notification small-icon glyphs and generated-app Factory artwork also remain unchanged. No permission, model/tool behavior, storage migration or network capability is added.

Validation is limited to affected launcher, mascot, toolbar and custom-bot regression seams plus exact artifact review. Host mask/density previews are illustrations; physical launcher/OEM/splash acceptance remains unverified. Software validation, exact APK audit and accepted ZIP delivery are complete.

## Validation and delivery

Affected coverage is Full 68 (62 unchanged cases plus six fresh complete-class replacements) and Play 68 fresh cases. Two failed fixture attempts remain preserved: Android traversal had not settled a replaced keyed surface before touch injection; the existing draw helper fixes only the test, retaining gestures and assertions. The original diagnostics and successful six-case direct check are retained. No unrelated app-suite result is presented as a new pass. Core/runtime/SDK retention binds 332 unchanged sources, the separately reviewed independent manual icon-generator change, 108 exact resolved inputs and unchanged template/SDK artifacts.

Final tests and three builds passed; both host lint reports are byte-identical to v89 and preserve their task failures with 46/37 historical errors. Exact DEX, resources, ART profiles and signed payloads passed independent review. Release AAPT changes RGB only in 452 fully transparent mascot pixels; alpha and every visible pixel remain exact.

The D7-signed ARM64 APK is 21,311,123 bytes, SHA-256 `c1fb78dd5b7fc6651b459f34eef9fac6d185de1670778886e0b6b6c38f7d8710`. Direct attachment exceeded the 20 MiB chat limit. The exact APK was sent in a lossless ZIP accepted on 12 October 2026 at 20:41:26 UTC: 19,757,301 bytes, SHA-256 `e6440d7ca51b6aa31293168d4d2be4257adaef37d80360c9e50951179b2a41da`. Persisted ZIP readback, CRC and extracted APK equality were verified. Extract the ZIP before installation. This does not establish download, installation or physical acceptance.

## Central scale follow-up v91 (not yet delivered)

The central chat mascot grows from 30 to 84 dp (2.8×), preserving ContentScale.Fit. Its touch target is 84 dp; CenterAlignedTopAppBar reserves 96 dp plus the existing Android window insets. Other routes retain the 30 dp image and 48 dp target. The real shell propagates measured header height to chat content padding. No image, launcher, bot, animation or permission changes are included.

Twelve focused tests per flavor pass, covering narrow portrait, landscape, an injected 24 dp status bar, centering, content clearance and actual touch targets. A preliminary harness failure before test execution is preserved; the corrected offline run passed. Acceptance is proportional: one fresh FullRelease build with mandatory Vital, exact Release artifact review and retained unchanged module evidence; v90 host lint stays historical, not freshly rerun. Release signing and delivery remain pending.
