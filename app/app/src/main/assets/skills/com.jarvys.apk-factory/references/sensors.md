# Factory guidance factory-guidance-v90 / sensors

## Finite capture
Declared sensors, installed foreground page; preview UNAVAILABLE, including list/cancel. Fixed kinds: light (lux/scalar), accelerometer (m/s2/device-natural), gyroscope (rad/s/device-natural). No vendor/ID enumeration. Discovery is no output guarantee. TYPE_GYROSCOPE angular velocity, no orientation/fusion/uncalibrated fallback. Natural x right/y top/z out, right-hand rotation, no display remap. Accelerometer includesGravity:true; others false.

- sensors.list() or {} → {sensors:[{kind,available,unit,frame}],mode:"installed"}.
- sensors.capture({kind,durationMs,intervalMs,maxSamples}): all four required, strict integers. durationMs100–300000, intervalMs100–1000, maxSamples1–50, additionally constrained by signed budgets. Defaults retain maximum duration5000ms/sample50, not a universal five-second ceiling.
- Completion {kind,unit,frame,includesGravity,reason,samples,mode:"installed",simulated:false}; reason sample_limit|duration; samples {elapsedMs,values:[number]} or [x,y,z]. elapsedMs is monotonic receipt-relative time, not hardware acquisition/wall time. Full result≤16KiB.
- sensors.cancel() or {} → {cancelled:boolean}; rejects active capture CANCELLED. Unregister failure rejects UNAVAILABLE, discards data and latches failure; later calls cannot claim successful cleanup. Valid cancel envelope≤1KiB uses bounded control admission.

Finite capture≤10Hz across captures/kinds,possibly zero light samples. One capture; no interpolation/rate guarantee,no automatic restart or missing-reply retry. Lifecycle loss cancels.

## Foreground subscriptions
- sensors.subscribe({kind,samplingPeriodUs}) retains the native subscription until it is ended or cleaned up. samplingPeriodUs actual integer0 (fastest) or4..2147483647; 1/2/3 are Android delay aliases and rejected. Signed minimumIntervalUs rejects faster requests; it does not throttle returned events.
- sensors.poll({subscriptionId}) returns metadata plus samples, receivedSamples,droppedSamples,invalidSamples,countersSaturated,active:true. Each sample has decimal-string sequence/timestampNs, elapsedMs,ageMs,eventBeforeSubscription,values. Clock elapsedRealtimeNanos; a first event may predate subscription and is labelled. Poll drains delivered bounded ring samples, not an unbounded callback stream.
- sensors.unsubscribe({subscriptionId}) → {subscriptionId,unsubscribed:true} after owned cleanup. Unknown/foreign/stale IDs fail.

Metadata includes unit/frame/includesGravity, requestedSamplingPeriodUs, bufferCapacity,foregroundOnly:true,rateGuaranteed:false,mode:"installed",simulated:false. Fixed ring max50 samples, oldest overwritten with droppedSamples reported. One subscription per helper, process max8 listeners. Signed resources.sensors: captureDurationMs default5000/max300000; captureSamples50/max50; minimumIntervalUs100000/range0..1000000; bufferedSamples50/max50, captureSamples≤bufferedSamples. Native emitted events are not arbitrarily capped to10Hz for subscriptions. HIGH_SAMPLING_RATE_SENSORS is selectively declared; actual Android permission/rate/privacy behavior still applies. Requested rate never proves observed rate.

Exact-page/foreground operation lifetime; pause/navigation/revocation drops undelivered samples. No host lease is needed. Failed unregister retains admission; no hardware-stop guarantee/recreation bypass. Avoid logging/health inference.

## Step counter
Separate steps capability. steps.status({}) discovers actual step-counter/permission/foreground/cleanup state without a retained count. steps.requestPermission({}) uses ACTIVITY_RECOGNITION on API29+; API24–28 needs no dangerous grant. No body-sensor/background grant or accelerometer fallback.

steps.current({timeoutMs?}): default15000, integer1000..120000. Returns first valid TYPE_STEP_COUNTER event then unregisters: {status:"measured",stepsSinceBoot,sensorType:"step_counter",counterScope:"since_boot_while_sensor_activated",resetsOn:"system_reboot",sensorTimestampNanos,sensorAgeMs,eventBeforeRequest,foregroundOnly:true,continuousTracking:false}. Timestamp is decimal-string monotonic nanos, not epoch. Count is device-reported safe integer while activated since reboot, not today/complete history. No zero on timeout, cached sample, daily delta/reboot detection or restored precision.

steps.cancel({}) returns status,cancelled,cleanupFailed,foregroundOnly:true; unconfirmed cleanup says cleanup_failed. Eight process listeners max; lifecycle loss cancels, failed cleanup retains admission. Status grants no measurement authority.
