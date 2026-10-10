# Factory local strong biometrics v80: implementation and evidence status

Version 80 / `1.2.73-FACTORY-BIOMETRICS` is in progress from published v79 baseline `5c177d3`.
This record claims no completed v80 final host aggregate, lint, independent APK audit, signed
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
