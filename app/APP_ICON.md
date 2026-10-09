# Jarvys application icon (UX32)

The owner supplied `artwork/jarvys-app-icon-source.png` on 2026-10-09 for future
Jarvys builds. Version 49 / 1.2.42-UX32 is the first build using it; the already
completed v48 artifacts remain unchanged.

## Source and preservation

- Original PNG: 1269 × 1240, RGBA, 1,093,892 bytes.
- SHA-256: `5e0f5ae21b58bb4fbdea80abc80af69cea3095354374293e5f1b06fe969558a4`.
- The source is retained byte-for-byte outside packaged Android resources.
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
- No monochrome mark is invented: a solid robot silhouette loses the supplied
  face and eyes. Android 16 QPR2 and later may automatically theme icons even
  without an application-provided monochrome layer; launcher behavior varies.
- No separate splash graphic or startup code is introduced. Android 12+ uses
  the launcher icon by default. Its actual system splash and OEM animation
  behavior still require physical-device acceptance; host icon previews are
  not system-splash screenshots.

The source includes near-invisible alpha-1 pixels outside the robot. Some can be
masked by Android, as with any foreground's outer area; all meaningful artwork
(including antennae, hands and feet) must remain visible. Do not shrink the
robot to accommodate stray near-transparent background pixels.

## Separate icon systems

`drawable/ic_jarvys.xml` remains byte-identical for existing notification small
icons and actions. It is not replaced with a full-color launcher bitmap.
Its SHA-256 is `fcf9571890d636a4e6d65e199bdd0ecf47174ea29735a34df041bd667a27154f`.

The APK factory's `apk-runtime/src/main/res/drawable-nodpi/factory_icon.png`,
factory manifest and embedded template remain unchanged. Factory-created apps
have their own caller-supplied icon. Factory source icon SHA-256:
`4a20adff2fb6a52ef3db2dd5e2f703f82aaa76bb901bea409804b51b9c3bed6f`.

Bot artwork, connector icons, package ID, label, permissions and signing identity
are unaffected. No key is created by this feature.

## Acceptance

`LauncherIconTest` covers API 24/25 legacy resolution, API 26/32/35 adaptive
resolution, manifest identity, density dimensions, alpha, source hashes,
notification/factory separation and native-rendered launcher/round agreement.
Set `JARVYS_UX32_CAPTURE_DIR` to retain the native icon images. Run both complete
distribution test suites, runtime tests, JavaScript SDK tests and lint comparison
against the previous baseline. Verify final APK resource resolution, version,
unchanged permissions/factory assets, CRC, alignment and existing-key signature.

Review the actual foreground and legacy files at native sizes, plus circle,
squircle, rounded-square and square masks on light and dark surroundings. Host
checks do not establish installation, OEM launcher caching, themed-icon behavior
or physical startup correctness.

## Platform references

- [Adaptive icons](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)
- [Create app icons](https://developer.android.com/studio/write/create-app-icons)
- [Android splash screens](https://developer.android.com/develop/ui/views/launch/splash-screen)
