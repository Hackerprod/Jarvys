# Factory foreground sensors v79: design and evidence status

Version 79 / `1.2.72-FACTORY-SENSORS` passed the independently reviewed staged host checks
below. Three fresh APKs passed final independent binary/control-flow audit; signing and native
delivery are pending. No physical sensor capture, installation or device acceptance is claimed.
SDK 2/schema 1; 19 capabilities/39 methods.

## Reviewed bounded design

The standalone `sensors` grant exposes only list, capture and cancel for light/accelerometer
in installed foreground apps. All four capture arguments are required typed values: kind,
durationMs 100–5000, intervalMs 100–1000 and maxSamples 1–50. One active capture; max 10 Hz
output across sequential captures and kind changes; maximum complete result 16 KiB. Relative
monotonic receipt times, copied finite values and fixed units/frames; no absolute timestamp,
hardware identity, accuracy metadata, background, GPS, health, microphone, broker or new permission.
Cancel rejects active capture; pause/navigation/reset/destroy/revocation unregister and never
restart automatically. Cleanup failure discards data and disables the controller, with no
hardware-stop claim. Duration can return empty samples; light is on-change.

Preview list/capture/cancel must all reject UNAVAILABLE. Web Permissions-Policy adds sensor
denials, whose actual enforcement on a physical WebView remains unverified. Full contract:
[APK_FACTORY.md](../../app/APK_FACTORY.md), foreground sensors section.

## Reviewed interruption correction and retained attempts

Implementation checkpoint `0115849` passed the first complete six-suite aggregate: Full 3,517,
Play 3,135, runtime 76+76 and shared core 812+812, total 8,428, plus SDK35. Final review then
found a deterministic interruption gap: the SDK rejects all pending promises on a global bridge
rate-limit response, but a capture could remain registered until its bounded deadline. Correction
`2182755` cancels and unregisters before that response, discards buffered data, and preserves
cleanup-failure uncertainty. Two new regressions cover success, stale callbacks and failed cleanup;
the saturated-control test now isolates explicit cancellation without first causing global failure.

The production delta is exactly three lines in FactoryRuntime and the associated test-file delta.
No unrelated application, manifest, SDK, guidance, build or assertion changes were made. Independent
review approved staged validation: retain the prior unaffected Full/Play complete-suite evidence,
rerun host Factory/guidance integration plus every runtime/core test, and regenerate lint/APKs.
The first aggregate and its APKs remain intermediate evidence, never the corrected release.
The initial compilation also failed on callback self-initialization; the corrected compile passed.
No test assertion, guard, heap limit or lint suppression was weakened.

## Final staged host evidence

Validated source `218275573e8dbe9e941861b8374bcb5552ea383f`; app tree
`805c0cc19c5aa9b8bb50649c0e2aef2a238cff43`. All 1,117 final app inputs are identical across
final lint, staged tests, build and after-build snapshots.

- Reused prior complete app suites: Full 3,517 and Play 3,135, attributed to `0115849`.
- Fresh corrected-source host Factory/guidance integration: Full 888 and Play 888.
- Fresh complete generated-runtime suites: Debug 76 and Release 76.
- Fresh complete shared-core suites: Debug 818 and Release 818, including 84 synthetic sensor
  cases per variant across API 24/28/35. Real Android adapters use shadows, never hardware.
- Fresh staged total 3,564 JUnit cases and SDK35, zero failures/errors/skips. This is staged
  evidence, not a newly repeated six-suite full aggregate on the correction.
- All 17 final test JVMs have verified paired offline-guard installation/shutdown records.
  Two HTTP(S) attempts were blocked, one per targeted host flavor, zero runtime/core. The
  earlier full aggregate blocked 16. Counts are not attributed to individual testcases.
- Fresh exact lint diagnostic multisets remain 313/300/3/2, with no additions/removals/suppressions.
- Prior test identities and all 18 historical guidance sections remain. Real read_skill retrieval
  covers all eight version/hash-bound references, read-only scope and customization preservation.
  Parsed always-loaded core is 7,517 UTF-8 bytes (full asset 7,851), below unchanged 8/16 KiB bounds.

Final independent three-APK/control-flow audit passed. Actual manifest/assets/template and DEX
were checked, including cancellation before RATE_LIMITED, all 54 prior runtime lambda bodies
and the explained D8 close trampoline. No new permission/component/query or unexplained handler
change. All three builds completed against the final freeze.
FullRelease unsigned: 27,545,966 bytes, SHA-256
`0a713a5d880cb0e190aa8872adf6c67594ed4762605b72c6e6712182735fa943`.
No signed installable delivery is claimed by this unsigned artifact.

## Acceptance still open

Synthetic checks do not establish physical timing/accuracy, sensor availability, WebView
Permissions-Policy enforcement, hardware-stop guarantees, installation/update/data retention,
ARM64 behavior or zero-network behavior. Existing export/share/browser/editor capabilities retain
their own reviewed transmission/consent contracts. No actual device sensor, location, health or
microphone data was accessed. Remaining sensors, biometrics, backup, adaptive resources/local UI,
TTS/voice, F1/F2/F3, UX34 gates, UX44 documentary defaults and UX43-last remain pending.

## Official references checked October 10, 2026

- https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview
- https://developer.android.com/reference/android/hardware/SensorEvent
- https://developer.android.com/reference/android/hardware/SensorManager
