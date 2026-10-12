# Static Jarvys identity v90

Development only: version 90 / `1.2.83-MASCOT`. The delivered v89 APK is unchanged.

The user-provided RGBA PNG is preserved byte-for-byte as the source artwork and shared static mascot: 1254 × 1254, SHA-256 `2c003833dc3f14883705a3fdcc1e694ebec06e82ea8a15b6c14e0300737fef52`. The existing deterministic Pillow 12.3.0 generator produces the fifteen density-specific launcher/adaptive foreground PNGs. No artwork redraw, cleanup, new mask or background design is introduced. The static Compose renderer uses ContentScale.Fit.

Jarvys animation availability is closed for this identity. Even an old process opt-in or a supported activity mode cannot prepare the old robot renderer. The account-menu animation toggle, warning and visual-test entry are dormant; their underlying controls, consent data model and historical Rive assets remain available for a future explicitly reviewed integration. Navigation, background and route changes do not enable the old renderer. Custom bot mascots and their animation facilities remain unchanged.

The separately requested sad animation is not bundled or integrated by this change. Notification small-icon glyphs and generated-app Factory artwork also remain unchanged. No permission, model/tool behavior, storage migration or network capability is added.

Validation is limited to affected launcher, mascot, toolbar and custom-bot regression seams plus exact artifact review. Host mask/density previews are illustrations; physical launcher/OEM/splash acceptance remains unverified. Final tests, APK audit and delivery are pending.
