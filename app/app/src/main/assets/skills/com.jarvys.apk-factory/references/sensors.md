# Factory guidance factory-guidance-v85 / sensors

Use with core; grants no authority. Current basics/native-services supersede historical latest-only, extra-consent, totals, zero-permission/offline and pending-family claims below. API/data/quotas remain version-scoped.

## Bounded foreground sensors (v82)

`sensors` is the nineteenth capability; SDK 2/schema/protocol 1 stay unchanged. Its v79 totals
were 19 capabilities/39 methods. Only installed generated apps with this declared capability
can use `Jarvys.sensors`. No broker, new Android permission, native per-read confirmation,
network, background service, GPS/location, health, microphone or arbitrary sensor enumeration.
No other grant implies `sensors`. Preview rejects all three methods with `UNAVAILABLE`, even
list and cancel; it provides no simulated readings. Older templates require a reviewed rebuild.

- `sensors.list()` (or `{}`) returns `{sensors:[{kind,available,unit,frame}],mode:"installed"}`.
  The fixed kinds are `light` (unit `lux`, frame `scalar`), `accelerometer` (unit `m/s2`,
  frame `device-natural`) and `gyroscope` (unit `rad/s`, frame `device-natural`). Availability is a Boolean; it is not a promise of future samples.
  No hardware names, vendors, IDs, accuracy, resolution or arbitrary sensor types are exposed.
- `sensors.capture({kind,durationMs,intervalMs,maxSamples})` requires exactly all four keys.
  `kind` is `light`, `accelerometer` or `gyroscope`. Duration is a typed JSON integer 100–5000 milliseconds,
  interval a typed JSON integer 100–1000 milliseconds, and maxSamples a typed JSON integer
  1–50. No strings, fractions, exponents, coercion, defaults or unknown keys.
  Only one capture may be active. Overlap fails rather than replacing the old operation.
- A completed capture returns `{kind,unit,frame,includesGravity,reason,samples,mode:"installed",
  simulated:false}`. `reason` is `sample_limit` or `duration`. Each sample is
  `{elapsedMs,values:[number]}` for light, or `{elapsedMs,values:[x,y,z]}` for accelerometer/gyroscope.
  `includesGravity` is false for light/gyroscope and true for accelerometer. Values are copied finite
  numbers; event-owned arrays are not retained. The complete serialized result is at most
  16 KiB, not merely its samples array.
- `sensors.cancel()` (or `{}`) rejects the active capture with `CANCELLED` and idempotently
  returns `{cancelled:boolean}`. False means no active capture was canceled. This does not
  undo previously delivered readings or erase copies retained by application code. If unregister
  fails, cancel rejects `UNAVAILABLE`, discards data and disables the controller. All later
  sensor calls, including cancel, reject `UNAVAILABLE` while cleanup failure remains latched;
  no success/false result claims cleanup. Lifecycle cancellation suppresses stale replies.
  The complete cancel wire JSON envelope is limited to 1,024 UTF-8 bytes; SDK empty-args
  requests fit this bound. This permits bounded cancellation outside ordinary admission limits.

`elapsedMs` is capture-relative monotonic receipt time in integer milliseconds, not wall time
or the Android hardware-event timestamp. It cannot establish exact physical acquisition time.
Accelerometer values include gravity and keep the device's natural coordinate frame, not
screen-rotated/world coordinates. Do not claim calibrated measurement, pose or health inference.

Gyroscope uses Android `TYPE_GYROSCOPE`, never the uncalibrated sensor. Its three values are
angular velocity in radians per second around x/y/z: x toward the device's right, y toward its
top and z outward from the screen in its natural orientation. Positive rotation follows the
right-hand convention. Display rotation does not remap the axes. This is not orientation,
pose, an angle, a fused rotation vector or a guarantee of calibrated accuracy/drift correction.
API 24+ can lack this hardware; an uncalibrated sensor alone is not a fallback. Inventory then
reports available:false and capture rejects UNAVAILABLE. No permission or broker is added.

Output is throttled to at most 10 Hz, including across sequential captures and kind switches;
intervalMs can lower that ceiling. A requested interval is not a guaranteed sample rate.
Light is on-change: a duration completion may contain fewer than maxSamples or zero samples.
No interpolation, duplicate fill, minimum rate or guaranteed hardware availability. Android
callback scheduling, sensor absence and OEM differences can affect observations.

Success, explicit cancel, pause, navigation, reset, destruction and capability revocation
invalidate the operation and unregister its listener. There is no automatic restart on resume.
Late callbacks cannot revive a revoked capture. An unregister failure discards collected data
and disables the controller; do not claim successful cleanup or that hardware stopped. Show
an honest error and stop collection rather than retrying around the failure. A missing reply
is not permission to restart. Sensors have no broker/native external-action recovery workflow.

Handle bridge/capability, invalid argument, unavailable, busy, cancellation and native failures.
Render loading/empty/canceled/error states; require an explicit next capture after interruption.
No readings in logs, telemetry or background loops. Separately declared storage/export/share
capabilities retain their own contracts and consent requirements.

The runtime adds sensor denials to WebView Permissions-Policy. Actual WebView enforcement,
physical output rates, sensor availability, lifecycle cleanup, rotation and ARM64 installation
remain unverified without authorized device testing. Synthetic fixtures prove only their tested
code paths, never hardware-stop guarantees. Remaining sensors, TTS/voice, backup, additional adaptive
local UI, F1/F2/F3, UX34 gates, UX44 documentary defaults and UX43-last stay pending.

Official Android references checked October 11, 2026:
- [Sensors overview](https://developer.android.com/develop/sensors-and-location/sensors/sensors_overview)
- [SensorEvent data and device coordinate frame](https://developer.android.com/reference/android/hardware/SensorEvent)
- [SensorManager registration, rate hints and unregister](https://developer.android.com/reference/android/hardware/SensorManager)

- [Motion sensors and gyroscope units](https://developer.android.com/develop/sensors-and-location/sensors/sensors_motion)
