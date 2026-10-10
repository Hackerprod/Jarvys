# Typed maps and dialer launch — UX42 F1, v72

Final frozen-source host validation and independent three-APK audit passed. Native signed delivery was accepted.
No physical Android acceptance is claimed.

## Scope

Separate `maps` and `phone` capabilities expose `maps.open({latitude,longitude})` or
`maps.open({query})`, and `phone.dial({number})`. Closed constructors create geo ACTION_VIEW
and tel ACTION_DIAL only. No arbitrary URI, flags, extras, component or recipient from JavaScript;
no GPS/contact read, CALL_PHONE path, automatic call or new generated/host permission.
Generated apps retain the two exact host package queries and their zero-permission manifest.
Jarvys host already has unrelated legacy permissions; only narrow host visibility and a separate
native review Activity are added here.

Native review shows complete immutable input and chosen recipient with data/network/accounts/
history/permissions disclosure. Exact latest-signed installed source identity and selected native
recipient pins are rechecked; no implicit fallback. Recipient eligibility is not a trust guarantee.
A third-party app's behavior is outside Jarvys control. `launchRequested` indicates only dispatch
acceptance; `actionConfirmed:false` cannot prove display, navigation, calling or delivery.

Separate Binder/journal/recovery domains preserve the v71 browser flow. Shared admission and
human-only guard remain durable through cancellation, unknown outcomes and pending preparation.
The new coordinator reserves its startup guard before asynchronous restore. Initial Binder setup
and recipient discovery also retain preparation ownership until terminal. No payload or recipient
is journaled, and no interrupted launch is replayed. Human acknowledgment after manually closing
the external task is a declaration, not independently verified OS closure.

## Validation and retained exploratory history

Official Android guidance and independent design review approved this bounded combined slice.
Independent early production review checked typed grammars, source/recipient identity, capability
separation, recovery, manifest scope and compacted Coding skill. Skill remains below 16 KiB.
Tests use synthetic values, package/Binder/lifecycle fixtures and intercepted native dispatch only;
no real call, map navigation, device location or user-data transmission.

The first shared-core run executed 453 cases with one fixture failure: a test mutated an original
JSONObject after the validator had copied it. The test now mutates the actual request's arguments;
production validation was not weakened. That run recorded zero guarded external HTTP(S) attempts.
The failing evidence is retained on the validation host, not published here.

Checkpoint `865b119` is backed up with normal history. Focused host validation passed 224 cases,
including 96 new native external-action cases; the single guarded worker blocked one external
HTTP(S) attempt, whose cause is not attributed. Corrected core/runtime validation passed 462+72
cases with zero failures/errors/skips and zero blocked external attempts across two guarded workers.
SDK passed 18 cases. Initial lint completed with one additional CustomSplashScreen warning in
each host flavor (314/301 versus 313/300), caused by the new Activity name. The narrow
FactoryExternalActionActivity rename updates exact manifest/runtime/recovery/test references;
no suppression, permission or dispatch behavior changed. The failed-delta evidence is preserved;
all final gates were repeated after the rename. Five actual-Application startup tests are included in the 1,066 frozen app inputs and passed the full aggregate.
None of these focused results substitutes for final aggregate, lint, APK audits or physical acceptance.

Full contract and current official sources: [APK_FACTORY](../../app/APK_FACTORY.md#typed-maps-and-dialer-launch-ux42-f1-v72).
Preserve v63–v71. Rich editors, TTS/voice and remaining F1 work stay pending; F2/F3 remain closed,
UX34 retains its gates and UX43 remains last.

## Final frozen-source receipt

Production checkpoint `10f74ca`, app tree `2f428d1c063dd41dd7d0c423ceea3a6f05bf4c3d`.
Documentation-only UX44 checkpoint `68fa6f0` does not change those 1,066 inputs.
Fresh aggregate: Full 3,289; Play 2,907; runtime 72+72; core 462+462, total 7,264,
with zero failures, errors or skipped cases. Fresh SDK: 18/18. All 17 JVM guards have
matching install/shutdown evidence; 16 HTTP(S) attempts were blocked, eight per host flavor,
matching v71, with zero in runtime/core. Individual attempts are not attributed to tests.
All four freshly executed lint report tasks preserve the exact 313/300/3/2 diagnostic multisets;
no added/removed diagnostics or new suppressions. Independent source, history, gate freshness,
v63–v71 case preservation and actual three-APK audit passed. Historical v64 memory-case
migration and previously strengthened permission tests remain disclosed in preserved evidence.

Actual unsigned APKs, v72 / `1.2.65-FACTORY-ACTIONS`:
- FullDebug: 35,144,428 bytes; SHA-256 `627fe0863e74ef1f22658a8a72c2a5a28b6be55147aa21225e9314e2a3f7224f`.
- PlayDebug: 33,532,546 bytes; SHA-256 `81e1dd11b1806f67aa64c61cc2bd3df9fe2e956a3bbea1af91e982d5a17cc2fc`.
- FullRelease: 27,442,017 bytes; SHA-256 `79427d95055a51c0f1f027743b1a7dc001865b4b41d52b0d77164082315f7d5b`.

Audit covers actual ZIP/DEX/manifests/resources, unchanged permissions/native assets, embedded
zero-permission runtime templates and absence of the old Activity name. Host tests and binary inspection do not establish
physical installation, Binder behavior, external rendering, navigation, calls, network delivery,
verified external-task closure or instantaneous cross-process cancellation. No real external
app launch or user-data transmission occurred during these tests.

## Signed native delivery

Native attachment of `Jarvys-Factory-actions-full-v72-arm64-test.apk` was accepted on
10 October 2026 at 11:46:29 UTC. Size: 19,244,603 bytes; SHA-256
`9c9d681dbb5dfa7aef2d183d67ce9fbba971f551468f75ac1124704fbec3f657`.
The existing approved D7 debug signer verifies with APK v2/v3; CRC and 16 KiB ZIP alignment pass.
The manifest and all 239 retained unsigned-source ZIP entries are byte-identical; only nine
non-ARM64 native libraries are omitted, with signature metadata added. Package remains
`com.jarvys.agent`, version 72 / `1.2.65-FACTORY-ACTIONS`, ARM64, minimum API 24.
This test key does not update an older A6-signed installation. Accepted attachment does not
prove download, installation or physical acceptance. No credentials or host reports are published.
