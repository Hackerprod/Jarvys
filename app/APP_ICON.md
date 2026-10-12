# Jarvys application icon (static identity v90)

The owner supplied the current `artwork/jarvys-app-icon-source.png` on 2026-10-12.
Version 90 / 1.2.83-MASCOT is the first planned build using this new identity;
previous delivered APKs remain unchanged. See [scope and status](../docs/MASCOT_STATIC_IDENTITY.md).

## Source and preservation

- Original PNG: 1254 × 1254, RGBA, 834,511 bytes.
- SHA-256: `2c003833dc3f14883705a3fdcc1e694ebec06e82ea8a15b6c14e0300737fef52`.
- The source is retained byte-for-byte in artwork and the shared static mascot resource.
- Its partial transparency, small matte remnants, pose and colors are preserved.
  No repainting, cutout cleanup, alpha normalization or generated artwork is used.

`python3 tools/generate_launcher_icons.py` generates the fifteen density PNGs.
Use Pillow 12.3.0 for byte-reproducible encoding. `--check` compares each committed
asset with a fresh derivation. `--previews DIRECTORY` writes review-only images
outside the APK. Generation is an explicit maintainer operation, not a Gradle
dependency, so building the app needs no Python or Pillow installation.

## Android resources

- Both distribution flavors inherit the main manifest's dedicated
  `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round` resources.
- API 24–25: legacy and round PNGs at 48, 72, 96, 144 and 192 px. The complete
  artwork is proportionally fitted to 40 dp width inside a 48 dp canvas, on the
  previous icon's `#111827` background. Legacy uses a rounded square; round uses
  a circle. Only outer background corners are transparent.
- API 26+: adaptive icon XMLs reference an opaque color background and a
  transparent PNG foreground at 108, 162, 216, 324 and 432 px. The complete source
  is proportionally fitted to 60 dp width and centered within the 108 dp layer.
  Its meaningful artwork fits the central 66 dp safe circle. The central 72 dp
  viewport maps to the same apparent scale as the legacy icon.
- No monochrome mark is invented: a solid mascot silhouette loses the supplied
  face and eyes. Android 16 QPR2 and later may automatically theme icons even
  without an application-provided monochrome layer; launcher behavior varies.
- No separate splash graphic or startup code is introduced. Android 12+ uses
  the launcher icon by default. Its actual system splash and OEM animation
  behavior still require physical-device acceptance; host icon previews are
  not system-splash screenshots.

The source includes near-invisible alpha-1 pixels outside the mascot. Some can be
masked by Android, as with any foreground's outer area; all meaningful artwork
(including the curled tuft, ears, hands and feet) must remain visible. Do not shrink the
mascot to accommodate stray near-transparent background pixels.

## Separate icon systems

`drawable/ic_jarvys.xml` remains byte-identical for existing notification small
icons and actions. It is not replaced with a full-color launcher bitmap.
Its SHA-256 is `fcf9571890d636a4e6d65e199bdd0ecf47174ea29735a34df041bd667a27154f`.

The APK factory's `apk-runtime/src/main/res/drawable-nodpi/factory_icon.png`,
factory manifest and embedded template remain unchanged. Factory-created apps
have their own caller-supplied icon. Factory source icon SHA-256:
`4a20adff2fb6a52ef3db2dd5e2f703f82aaa76bb901bea409804b51b9c3bed6f`.

Custom-bot artwork, connector icons, package ID, label, permissions and signing identity
are unaffected. Jarvys chief/captain surfaces use the same new static identity. No key is created by this feature.

## Acceptance

`LauncherIconTest` covers API 24/25 legacy resolution, API 26/32/35 adaptive
resolution, manifest identity, density dimensions, alpha, source hashes,
notification/factory separation and native-rendered launcher/round agreement.
Set `JARVYS_UX32_CAPTURE_DIR` to retain the native icon images. Run the reviewed affected launcher/mascot/toolbar/custom-bot classes in both
distributions and compare lint against the previous baseline. Retain unchanged
Factory/runtime/SDK evidence only with exact source and input equality. Verify final APK resource resolution, version,
unchanged permissions/factory assets, CRC, alignment and existing-key signature.

Review the actual foreground and legacy files at native sizes, plus circle,
squircle, rounded-square and square masks on light and dark surroundings. Host
checks do not establish installation, OEM launcher caching, themed-icon behavior
or physical startup correctness.

## Platform references

- [Adaptive icons](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)
- [Create app icons](https://developer.android.com/studio/write/create-app-icons)
- [Android splash screens](https://developer.android.com/develop/ui/views/launch/splash-screen)
