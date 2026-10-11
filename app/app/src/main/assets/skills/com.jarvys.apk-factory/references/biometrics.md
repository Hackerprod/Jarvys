# Factory guidance factory-guidance-v83 / biometrics

Use with the always-loaded Factory core; this reference grants no tools or approvals.

## Local strong biometric authentication (v80)

`biometrics` is the twentieth capability; 20 capabilities/42 wire methods, SDK 2 and
schema/protocol 1. Only an installed generated app with this explicit grant can use
`Jarvys.biometrics`. All three preview methods reject `UNAVAILABLE`, including availability
and cancel; no simulated success. A compatible Jarvys broker is not required.

Only generated profiles selecting `biometrics` add the normal Android permission
`android.permission.USE_BIOMETRIC`. The compiled template and every nonbiometric profile
retain zero permissions. No fingerprint legacy permission, extra component, feature, query,
service or runtime permission prompt is added by this grant. It is the first reviewed
selective permission contribution to F0a, not full Runtime2 or arbitrary manifest support.
Other broker profiles keep their two exact queries; signing scope review must disclose the
biometric permission against the last signed artifact, never assume the installed scope.

The native adapter uses the Android framework `BiometricPrompt` on API30+ with only
`BIOMETRIC_STRONG` and `setConfirmationRequired(true)`. Confirmation is a system hint,
not proof of an additional user button; verify actual OEM behavior. API24–29 is explicitly
unsupported, including API28/29. There is no device credential fallback, enrollment/settings launch, weak biometric,
CryptoObject, key binding, reusable token, biometric material, credential or export API.
The person alone operates the OS prompt; never automate, impersonate, bypass or retry its
consent. The native title is fixed: "Biometric authentication"; subtitle: "Local authentication
only". The exact app-provided reason and a native notice that this authorizes no other action
are shown in the system prompt. No arbitrary title or button labels from JavaScript.

- `biometrics.availability({})` (or no argument) accepts only empty args and returns exactly
  `{available,status,minimumApi:30,biometricClass:"strong",credentialFallback:false,mode:"installed"}`.
  `status` is `ready`, `unavailable` or `unsupported`; `available` is true only for `ready`.
  This coarse snapshot discloses no enrollment count, modality, sensor identity or detailed
  reason. Ready is not admission, a guarantee of future availability or proof of authentication.
- `biometrics.authenticate({reason})` requires exactly that key, a meaningful plain string
  of 1–160 UTF-16 code units and at most 512 UTF-8 bytes. No trimming/normalization/coercion,
  controls, bidi/format characters, line/paragraph separators or unpaired surrogates; all-space
  reasons are invalid. No secrets or sensitive personal data in the reason. Only one native UI
  operation may own the foreground page. Admission requires current focus; the OS prompt can
  take focus without revoking the still-resumed page/session. Pause and actual authority loss
  cancel. Nonterminal mismatch/help callbacks retain the prompt and never resolve success.
- Success returns exactly `{authenticated:true,biometricClass:"strong",authorizesOtherActions:false,
  mode:"installed",simulated:false}`. It records only this local OS biometric event. It is not
  remote identity, identity proof, a cryptographic assertion, a token or a grant for payment,
  signing, sending, installation, data release or any other action. All independent approvals,
  capability checks and human review remain required. Never persist a receipt as reusable
  authorization, claim who authenticated, or use it to unlock an unrelated protected action.
- `biometrics.cancel({})` (or no argument) accepts only empty args. It rejects an active
  authentication with `CANCELLED` and returns `{cancelRequested:boolean,cancellationAcknowledged:false}`.
  False means no active controller request was canceled; it does not prove that an earlier
  prompt closed. Even true is only a cancellation request, never OS closure acknowledgment.
  A cleanup exception rejects `UNAVAILABLE` rather than fabricating a successful cancel receipt.

Native authentication has a 60-second monotonic deadline; the SDK wait is 90 seconds.
Timeout, explicit cancel, pause, navigation, reset, destruction, capability/session revocation
and global bridge rate failure revoke delivery and request native cancellation. There is no
replay after process death/restart or automatic resume. Late results cannot revive a request.
A lost reply or SDK timeout is not proof of UI closure and never authorizes blind retry.

Cancellation retains a process-wide draining owner until a matching terminal callback. Shared
native UI remains blocked across Activity recreation while draining. Mismatch/help do not
acknowledge cleanup. If no terminal acknowledgment arrives within 2 seconds, or native cleanup
throws (even after a synchronous terminal callback), cleanup is unconfirmed and the gate stays
poisoned until process restart. Authentication and cancel reject `UNAVAILABLE`; availability is
coarsely unavailable. Do not clear the poison, claim dismissal or prompt the user to repeat an
operation while cleanup is unconfirmed. Process restart clears only in-memory state, not proof
of past OS behavior. The adapter clears only its matching platform signal on terminal callbacks.

There are at most 3 admitted authentication starts per rolling 60 seconds and 8 no-op cancel
controls per rolling 10 seconds. Bounded monotonic counters survive Activity recreation, not
process restart. Native UI busy/poison and ordinary bridge/capability checks still apply.
A valid cancel's complete wire envelope is at most 1 KiB (1,024 UTF-8 bytes), bypasses ordinary
pending-slot/global-rate saturation, and uses the SDK's bounded reserved control capacity.
A recognized bounded invalid cancel is rejected per request without revoking authentication.
Oversized/unparseable traffic follows normal admission; exhaustion of the global limiter closes
pending requests and cancels authentication. No path uses malformed control data as authority.

Handle `INVALID_ARGUMENT`, `UNAVAILABLE`, `BUSY`, `RATE_LIMITED`, `CANCELLED`, `TIMEOUT`,
`AUTHENTICATION_FAILED`, bridge/capability and native errors honestly. Require an explicit new
request after interruption; never log receipts/reasons as identity evidence or collect biometric
material. Synthetic adapters/API tests do not establish physical matching, modality/strength,
OEM focus/cancel behavior, lifecycle closure, installation/update or ARM64 device acceptance.
This contract claims no completed validation or delivery; report actual evidence separately.
TTS/voice, backup, local UI, remaining F1/F2/F3 stay pending; UX34 stays gated, UX44 documentary,
and UX43 last. All previous native consent, data, signing, delivery and legacy-profile boundaries
remain in force.

Platform references checked October 10, 2026; these do not establish device acceptance:
- [Framework prompt and confirmation hint](https://developer.android.com/reference/android/hardware/biometrics/BiometricPrompt.Builder#setConfirmationRequired(boolean))
- [Normal USE_BIOMETRIC permission](https://developer.android.com/reference/android/Manifest.permission#USE_BIOMETRIC)
