# Factory local strong biometrics v80: scope and evidence

Version 80 / `1.2.73-FACTORY-BIOMETRICS` has passed the complete final host aggregate, SDK
and controlled-baseline lint comparison. Independent review approved all three final host APKs
and the signed delivery bytes. No physical authentication, installation or device acceptance is claimed.
SDK 2/schema/protocol 1; 20 capabilities/42 methods.

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

## Final host evidence

Validated checkpoint `d815b19b27f2f0b18a8d9325924ef7225790bb0e`; app tree
`119dac1fee793fa306656438447f491cad0a7cb0`. All 1,123 inputs are frozen. The two final
corrections affect tests only; production inputs are unchanged from `99b9f34`.

- One complete fresh six-task aggregate: Full 3,528, Play 3,146, runtime Debug/Release 78+78,
  shared core Debug/Release 973+973, total 8,776. Zero failures/errors/skips.
- All 41 SDK cases passed with exact names/order checked against source.
- All 17 Test JVMs have paired reviewed guard installation/shutdown receipts. Sixteen HTTP(S)
  attempts were blocked; this does not establish general OS-level or device network isolation.
- API24/28/29 unsupported behavior and API30/35 strong-only adapters, success/error/mismatch,
  stale callbacks, deadline, origin/session/lifecycle cancellation, drain/poison, concurrency and
  reserved cancel controls use synthetic fixtures and shadows, never real biometric data.
- Fresh complete lint diagnostic multisets match reconstructed v79 exactly: 321/311/5/4, with
  zero additions/removals. Both sides used the same 66 pinned metadata/version-marker inputs,
  verified before and after. Historical 313/300/3/2 counts remain historical and unreproduced
  after the executor reset; their original XML/cache was lost. No diagnostic was suppressed.
- Real read_skill retrieval covers nine version/hash-bound guidance references and all 18
  historical v77 sections. Parsed core 7,673 UTF-8 bytes (raw 8,007); unchanged 8 KiB core,
  16 KiB per-reference and 64 KiB total-reference bounds, with read-only Coding/customization
  and disabled/usage/profile boundaries retained.

Both Full/Play generated fixture groups passed independent binary review: the exact four closed
permission/query trees, biometric-only and biometric-plus-query signed pairs, legacy permissionless
pairs, unchanged template DEX/payloads, certificate binding, v2/v3 signatures and ZIP alignment.
Only selected biometric profiles declare USE_BIOMETRIC; the bare template remains permissionless.

All eight exhaustive matrix instances remain enabled at 1,048,576 selections. Their summed case
time was 4,630.130 seconds; aggregate wall time was 7,267.273 seconds. The two bridge matrices
alone took 2,067.081 and 2,112.006 seconds. These are measurements on the restored executor,
not a direct performance comparison with the previous machine. Future factoring requires
independent compositional proof; this release made no coverage reduction.

## Retained failures and recovery boundaries

The executor reset interrupted earlier validation. Pinned official tooling and the reviewed
HTTP(S) guard were restored, and final evidence was run afresh rather than claimed recovered.
Two later FullDebug attempts each completed 3,528 cases with one failure; remaining aggregate
tasks were not run after those failures. First, a preview test expected 42 observations although
42 wire methods plus three host checks produce 45. Its correction also requires exact biometric
preview-denial observations and the biometric-only subset; focused Full/Play checks passed 14+14.
Second, an unchanged memory-review test could observe persisted approval before UI completion
and click a disabled tab. The reviewed two-line strengthening waits for the existing success
indicator and enabled tab, retaining the original 10-second bound and duplicate-grant assertions.
The timeout alone does not prove that exact interleaving. Focused checks of both corrected classes
passed 29+29. The final complete aggregate above passed; it uses `--continue` only for discovery
when dependencies permit, and still rejects any failed or incomplete task.

The reconstructed build-order helper adds the release-template producer dependency only to the
two proven FullRelease vital-model/analysis consumers. Normal build lint tasks remain enabled.
Complete four-report lint separately excludes only the redundant runtime lintVitalRelease task
affected by the reviewed AGP report-order defect; full runtime lint analysis/report still runs.
Failed build attempts remain separate. No heap, assertion, timeout or guard protection was weakened.
Binary-audit helpers also stopped on unsafe ordinal-map collisions and a UTF-16/JSON representation
mismatch before approval. Their narrow, independently controlled corrections preserve complete
values, incoming references and collision rejection; no application payload was exempted.

The delivered v79 APK was recovered and independently bound to the reconstructed baseline.
Compiler switch-tail/trampoline and profile-index differences received exact bounded control-flow
and profile-semantic proofs; this is not a claim of byte-reproducible builds. All other common
payloads and the known ARM64/signature packaging boundary were checked explicitly.

## Final APK and delivery

Independent complete binary review approved FullDebug, PlayDebug and FullRelease, including
complete changed-DEX/class coverage and explicit bounded compiler/source correspondences.
Redundant generic ordinal-discovery runs were stopped without a passing verdict; the final
strict complete-delta and incoming-reference proofs supplied the approved coverage instead.

Unsigned final APKs:
- FullDebug: 35,281,828 bytes, SHA-256 `93d617874fcf13fab4bd30fcc2f2d039bf7274421915a6bf5b18e1297fd5c6fb`.
- PlayDebug: 33,670,318 bytes, SHA-256 `573347a6e26d248124d3fb3ff01b87b212154d5e86bc32083f9b2f3a26f1f3d4`.
- FullRelease: 27,560,581 bytes, SHA-256 `d79f0fa0f7bdb12a04120799daf0fff67ffa7ba9ec6bfe38e4adaa002837a850`.

Native delivery of `Jarvys-Factory-biometrics-full-v80-arm64-test.apk` was accepted on
2026-10-11 at 03:34:08.595109 UTC: 19,364,467 bytes, SHA-256
`d9339b80f892194e1e17626ed82554be26b88c90fef385f0e455423bd2fea987`.
The existing D7 test certificate, v2/v3 signatures, ZIP CRC, absence of duplicate entries,
16 KiB ZIP alignment, identity/version and manifest/permissions were independently verified.
All 249 retained payload entries match the approved FullRelease input exactly; only nine
non-ARM64 libraries were omitted and three signing metadata entries added. Independently
retrieved persisted Library bytes match the delivered file exactly. This verifies upload
persistence, not download, installation or device receipt. ZIP alignment does not prove
ELF compatibility with 16 KiB device pages.

## Acceptance still open

Only the user operates the real OS biometric prompt. Host evidence does not establish physical
matching, actual enrollment/hardware availability, OEM strength/modality/confirmation behavior,
focus transfer, prompt closure, cancellation timing, installation/update/data retention or ARM64
device behavior. No enrollment/settings or actual device biometric tests were performed.
Availability is coarse and local; a success receipt is not remote identity, a token or authority
for an unrelated action. Signature/delivery acceptance does not establish download or installation.

TTS/voice, backup, adaptive resources/local UI, remaining F1 and F2/F3 stay pending. UX34 retains
its separate gates, UX44 is documentary and UX43 remains last. v63–v79 boundaries remain intact.

## Recoverable validation sources

[host-harness](host-harness/README.md) preserves source-only restoration, pinned tools/SDK/guard,
fresh gate orchestration and fail-closed archive checks. [reviewer-tools](reviewer-tools/README-REBUILT-HELPERS.md)
preserves binary/manifest/DEX/profile and fresh-evidence audit sources and synthetic controls.
Materialize outside Git as documented. Generated APKs/probes/JARs, signing material, credentials
and raw host reports are not published in the repository.
