# Factory foreground sensors v79: design and evidence status

Status: implementation and verification in progress. No test, build, installation or physical
sensor result is claimed by this initial record. Record actual commands, results and artifact
hashes only after they have run. SDK 2/schema 1; 19 capabilities/39 methods.

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

## Required evidence, not yet recorded here

- Strict validator/catalog/dispatcher parity, forbidden/unknown kinds and exact typed bounds
- Deterministic fake backend: copied finite samples, output throttling across captures/kinds,
  deadline/empty completion, sample cap, exact serialized bound and stale callback rejection
- Cancel, register/unregister failure, overlap, all lifecycle/revocation paths and no restart
- SDK frozen API/options/errors/timeouts/pagehide; all 39 catalog methods
- Eight-module real read_skill retrieval, hashes/version/core budget, unchanged prior 18
  section preservation and customized/read-only access tests
- Host builds and template verification as applicable, with commands/results/hashes recorded
- Separately authorized physical ARM64/WebView and OEM sensor checks; installed availability,
  visible foreground lifecycle behavior, cleanup, Permissions-Policy enforcement and updates

Synthetic tests cannot establish physical sensor timing/accuracy, actual WebView enforcement,
hardware stop, installation/update, ARM64 or zero-network behavior. Remaining F1/F2/F3 and
UX34/UX44/UX43 gates remain unchanged. Do not turn this design checklist into passed evidence.

## Official references checked October 10, 2026

- https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview
- https://developer.android.com/reference/android/hardware/SensorEvent
- https://developer.android.com/reference/android/hardware/SensorManager
