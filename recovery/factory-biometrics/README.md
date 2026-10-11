# Factory local strong biometrics v80: implementation and evidence status

Version 80 / `1.2.73-FACTORY-BIOMETRICS` is in progress from published v79 baseline `5c177d3`.
This record claims no completed v80 final host aggregate, independent APK audit, signed
artifact or delivery. Do not reuse earlier evidence as if it validated the final v80 source.
SDK 2/schema/protocol 1; 20 capabilities/42 methods. v79 remains the latest completed delivery.

## Bounded native contract

`biometrics.availability({})`, `authenticate({reason})` and `cancel({})` require the explicit
biometrics grant and installed foreground runtime; every preview method rejects UNAVAILABLE.
The standalone framework adapter needs API30+ and BIOMETRIC_STRONG. API24–29 is unsupported.
Only selected generated profiles add normal `android.permission.USE_BIOMETRIC`; the compiled
template and all nonbiometric profiles retain zero permissions. No other feature/component/query
or permission is contributed. Existing broker-family queries remain exact and independently scoped.
This first selective permission expands reviewed F0a construction, not full Runtime2.

Only the person operates the fixed-title system prompt. Native `setConfirmationRequired(true)`
is a system hint, not evidence of an extra confirmation button on every device. The exact reason is meaningful plain
text, 1–160 UTF-16 units/at most 512 UTF-8 bytes, without controls, bidi/format characters,
line/paragraph separators or unpaired surrogates. No device credential fallback, weak biometrics,
enrollment/settings launch, CryptoObject, key binding, token, biometric material or export API.
Availability is only the six-field coarse ready/unavailable/unsupported snapshot. Success is a
local OS event with `authorizesOtherActions:false`, never remote identity or reusable approval.
The exact API and receipt contracts are in [APK_FACTORY.md](../../app/APK_FACTORY.md#local-strong-biometrics-ux42-f1-v80).

Native deadline 60 seconds; SDK timeout 90 seconds. Mismatch/help retains the prompt. Pause,
navigation, reset, destruction, revocation and global bridge rate failure cancel without replay.
Admission requires focus; normal system-prompt focus transfer alone does not revoke a resumed
page. Cancellation returns `cancelRequested` and `cancellationAcknowledged:false`, never verified
UI closure. The process-wide draining gate blocks native UI across Activity recreation until a
matching terminal callback; 2 seconds without acknowledgment or a cleanup exception poisons it
until process restart. Synchronous terminal callback followed by cleanup throw still poisons.
Quotas are 3 starts/rolling 60 seconds and 8 no-op cancels/rolling 10 seconds across recreation.
Valid bounded 1 KiB cancel envelopes bypass ordinary saturation; invalid recognized controls
cannot revoke authentication. Oversized/unparseable traffic exhausting the global limiter closes
pending requests and requests cancellation. No effect or authentication is automatically replayed.

## Required verification, still open

- Validate strict argument/receipt shape, grant/preview denial, API24/28/29 unsupported paths,
  API30+ strong-only adapter, deadlines, quotas and the reserved validated cancel control.
- Exercise synthetic success/error/mismatch/help, late callback, native launch failure,
  foreground authority loss, recreation, concurrent native UI and drain/poison behavior, including
  missing acknowledgment, cleanup throw and synchronous callback-then-throw. These are synthetic
  cases; their existence does not establish execution or pass status.
- Verify selective manifest permission addition/removal, no duplicate/foreign permission,
  unchanged no-biometric and legacy profiles, template authentication, complete DEX/resource
  bindings, latest-signed scope review and payload identity before/after signing.
- Run final fresh host checks, SDK tests, exact lint and independent three-APK audit against frozen
  final inputs; preserve failed, interrupted and unrun attempts separately. No Gradle/test result,
  independent-review outcome or delivery receipt is asserted by this documentation checkpoint.
- Real read_skill must retrieve all nine factory-guidance-v80 references, including biometrics,
  with version headers and manifest SHA-256 verified, 18 v77 sections retained, read-only scope,
  and unchanged custom-skill/disabled/usage/profile boundaries. Budgets stay 8 KiB parsed core,
  16 KiB per reference and 64 KiB total references; no enlarged read budget.
- Authorized physical checks remain required for matching, modality/strength interpretation,
  OEM focus/confirmation/cancel behavior, lifecycle closure, installation/update/data retention
  and ARM64 behavior. Host/Robolectric/adapter fixtures cannot establish these outcomes.

All v63–v79 consent, lifecycle, manifest, signing and recovery boundaries remain. TTS/voice,
backup, local UI, remaining F1 and F2/F3 remain pending. UX34 retains its gates, UX44 is documentary,
and UX43 remains last. This work neither enables them nor closes their acceptance criteria.

## Recoverable validation sources

The executor reset interrupted final validation on 2026-10-10. The implementation and two
reviewed corrections were preserved through normal-history source checkpoint `99b9f34`.
Final v80 gates must be rerun against that frozen source; pre-reset checks are not final evidence.

[host-harness](host-harness/README.md) preserves source-only restoration, exact guard-hash gates,
SDK30/toolchain checksum pins, fresh gate orchestration and fail-closed archive helpers.
[reviewer-tools](reviewer-tools/README-REBUILT-HELPERS.md) preserves independent binary/manifest,
fresh-evidence and semantic DEX audit sources, with synthetic probe regeneration controls.
Materialize these tools into the documented external layout before use; rebuilding a guard
requires fresh checks and independent approval before any exact hash re-pin. The portable
launcher is a reviewed recovery source, not a claim that it ran the current final gates.
No generated APK, guard JAR, signing material, private data or raw host report is included.

### Fresh controlled lint after recovery

Fresh offline lint completed against reconstructed v79 and frozen v80 source on 2026-10-10.
Independent review verified identical complete diagnostic multisets for Full/Play/runtime/core:
321/311/5/4, with zero diagnostics added or removed by v80. Both runs used the same 66 pinned
metadata and version-marker inputs; their bytes and inventory were verified before and after.
Historical 313/300/3/2 counts remain historical and were not reproduced: the original XML and
metadata cache were lost at reset. No diagnostic was suppressed to match those old counts.
The candidate Gradle lint run succeeded; its wrapper initially rejected the historical fixed
counts. After independent approval, only those four reference constants were calibrated and
the exact-multiset comparator was rerun successfully. This was not another Gradle lint run.
Full aggregate tests, three-APK comparison, signing and delivery remain open.

### Test corrections before the final aggregate

Two complete FullDebug attempts each ran 3,528 tests with one failure; the remaining five
aggregate tasks did not run after those failures. The first exposed a stale preview expectation:
42 wire methods plus three host checks produce 45 observations. The corrected test also requires
the exact three biometric preview-denial observations and a biometric-only capability subset.
Its focused Full/Play checks passed 14+14 cases. The second exposed a possible IO-to-UI
synchronization race in an unchanged memory-review test: persisted approval could be observed
before the UI completed approval and re-enabled navigation. The reviewed test now waits for the
existing success indicator and enabled tab before continuing, retaining the same 10-second
bound and every duplicate-grant assertion. The timeout does not prove its precise interleaving.
Both changes affect tests only; production inputs remain identical. The next aggregate uses
`--continue` solely to collect results from all six independent tasks when dependencies permit; any failure still rejects
the gate. A complete all-green aggregate, fresh final-source lint and APK audits remain required.
