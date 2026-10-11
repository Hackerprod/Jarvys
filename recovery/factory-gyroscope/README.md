# Factory gyroscope v82: staged host evidence

Version 82 / `1.2.75-FACTORY-GYROSCOPE` adds bounded angular-velocity capture to the existing
foreground sensors grant. SDK 2/schema 1/sensors protocol 1 and 20 capabilities/42 methods
remain unchanged. No new Android permission, broker, required hardware feature or network path.

## Contract

The third fixed kind is `gyroscope`: Android TYPE_GYROSCOPE, rad/s, three copied finite x/y/z
values, right-handed natural-device axes and includesGravity:false. No uncalibrated fallback,
pose integration or measurement-accuracy promise. Missing hardware returns false availability
and UNAVAILABLE capture, including API 24. Preview rejects all sensor methods without inventing
readings. Light/accelerometer preserve their original units and behavior.

Same shared controller and limits: one capture, 10 Hz across sequential captures/kind changes,
100–5000 ms duration, 100–1000 ms interval, 1–50 samples and complete result at most 16 KiB.
Explicit cancel, pause, navigation/reset/destroy, focus/session/authority loss and global rate
failure discard buffered data. No automatic restart. Cleanup failure closes the controller;
no claim that hardware stopped. Full API details: [APK_FACTORY.md](../../app/APK_FACTORY.md#gyroscope-extension-ux42-f1-v82).

## Source and validation boundary

Final source `3e586193ee5cedd4de5d82bbf736373cee9118a1`; app tree
`37ecee34e59ce70539f2796a8dd549438d24f6f0`. All 1,141 tracked app inputs are unchanged through
tests, lint and builds. The final freeze JSON was written 9 seconds after the test launcher;
independent review verified every input predates launch and matches its frozen hash.

Independent review approved the exact SensorCapture hash before refreshing its proof pin:
`fc483b80bba74fd4226ae44ca499525553d55f3f601e08389d77944e7a4d3b7a`.
The only validator delta is a literal accepted kind. All 23 proof pins match; argument-only
pure validation and all original exhaustive method fixtures remain intact. Historical matrix
reports are preserved; this is a separate fresh v82 run.

Fresh staged JUnit checks: 2,604, zero failures/errors/skips.
- Full/Play affected host: 170+170, comprising 137+137 main and 33+33 supplemental.
- Complete generated runtime Debug/Release: 87+87.
- Complete shared core Debug/Release: 1,045+1,045.
- All 10 exhaustive 2^20 subset matrix instances rerun, including both FactorySpec instances.
- Gyroscope inherits every sensor runtime lifecycle test: 14 methods × 3 APIs × 2 variants = 84.
- Additional core fixtures cover calibrated-only adapter selection, absent hardware, copied
  finite xyz, extreme-value result bounds, shared throttling, revocation and failed cleanup.
- SDK 42 separately passed against the final source.

All 8 Test JVMs have paired offline-guard installation/shutdown records with original heap,
assertions and guards retained. Two intercepted Java HTTP(S) attempts are recorded without
URL/test/cause attribution. This is not a zero-egress claim.

The 6,340 unrelated historical host cases in 739 variant suites are retained as historical
context with unchanged test-source, dependency-declaration and retained toolchain bindings.
Changed shared production/assets remain explicit; no complete resolved-classpath inventory
was proven and prior outcomes are not asserted to transfer to v82. These cases are not fresh
or added to the 2,604 count. No new full-app aggregate claim. Main run 1693.570s; supplemental run 559.989s, without a benchmark claim.

Fresh lint diagnostic multisets remain exactly 321/311/5/4, equivalent to retained diagnostics
with known legacy errors and existing abortOnError=false. This is not lint-clean. No additions,
removals or new suppression.
All 66 frozen cache metadata files, 2 separately documented markers and 2 empty metadata
directories are preserved. Ten real version/hash-bound read_skill resources remain; core
body 7,675 UTF-8 bytes fits unchanged 7,680 headroom and 8/16 KiB contract bounds.

## Retained attempts

An initial gate was deliberately interrupted before a guidance headroom correction. One
redundant word was removed; no limit/assertion was weakened. An early SDK cross-realm test
assertion was corrected before freezing. A coordinator launch failed closed while the freeze
JSON was not yet present and was restarted. These attempts are not passing evidence.

## APK and delivery

Final independent three-APK audit approved. Actual manifests, all assets/resources, embedded
runtime templates, all DEX class inventories and baseline-profile producer bytes were checked.
FullDebug: 28,422 unchanged / 8 changed classes; PlayDebug: 26,059 unchanged / 8 changed classes;
FullRelease: 27,016 unchanged / 9 changed classes. No class/method additions or removals.
Expected source-driven changes plus one exact release-only unreachable NOP/switch-payload
alignment delta are individually closed; prior sensor lifecycle/cancellation paths are exact.
No new permission/component/query. All three embedded templates equal the reviewed template.

Approval receipt SHA-256:
`d2ae1b4724368e7e7e4a40354a653a5ed753cab5f8ae1b9199cf811c753b8f8d`.
Unsigned FullRelease: 27,585,796 bytes, SHA-256
`abdd47bfd4963b87365eca952639e8d8514aa9c51c19c1df2b544691db473fea`.
Signed ARM64 test APK native attachment accepted October 11, 2026 at 06:46:44 UTC:
`Jarvys-Factory-gyroscope-full-v82-arm64-test.apk`, 19,385,510 bytes, SHA-256
`91f0e570b4b62026ca3ae4ae480b3b7f7babdbdea2852f40a3fcb9b393e16294`.
Independent verification checked D7 test-identity v2/v3 signatures, ZIP CRC and 16 KiB ZIP
alignment. All 255 retained payload entries match the approved unsigned release; exactly
nine non-ARM64 libraries were omitted. Actual persisted Library bytes were independently
retrieved and match the signed artifact byte-for-byte. Signed/Library receipt SHA-256:
`521d99dce0c3d199ef5d99ca457f4d299b5e5455ba6ddf16a83abf2a746a1b45`.
Native acceptance does not establish download, installation/update, data retention, physical
sensor behavior or ELF 16 KiB page compatibility.

## Acceptance still open

No physical sensor capture, installation/update, ARM64 behavior, physical timing/accuracy,
rotation, WebView enforcement or hardware-stop acceptance claimed. Backup/restore, other
sensors, TTS/voice, additional adaptive UI and remaining F1/F2/F3 remain open. UX34 gates,
UX44 documentary defaults and UX43-last retain existing boundaries. No user sensor data
was accessed by these synthetic tests.

Primary references checked October 11, 2026:
- https://developer.android.com/reference/android/hardware/SensorEvent
- https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion
