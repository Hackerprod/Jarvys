# UX42 F1 v73: typed email and SMS editors

Preparation version 73 / `1.2.66-FACTORY-EDITORS`. Host implementation, validation and independent release audit passed; signed native delivery accepted.
This is a bounded capability slice, not completion of Factory or physical Android acceptance.

## Scope

Separate `email` and `sms` capabilities use the existing closed v72 external-launch broker.
Email accepts one conservative ASCII address and required subject/body strings; SMS accepts
one optional-plus ASCII digit number and a required body. Native-only constructors produce
ACTION_SENDTO with mailto/smsto and fixed subject/text or sms_body extras. No arbitrary URI,
component, extras, attachment, CC/BCC, contacts read, permission or automatic sending path.
All limits and exact grammar are documented in [APK_FACTORY](../../app/APK_FACTORY.md).

The recipient gets the complete fields on opening. Its network, account and cloud draft-sync
behavior are outside Jarvys control, and it may ignore or change the supplied values. Native
human review shows separate labelled values and selected app; dispatch alone does not prove
rendering, draft saving, sending, delivery or external-task closure. The receipt retains only
`launchRequested` and `actionConfirmed:false`. No false assertion that the recipient never sent.

Exact latest signed caller identity, pinned host and recipient identity revalidation, single-use
durable launch_pending authority, no replay, expiry/revocation and user-declared native recovery
remain from v72. Journal contains only state/open/nonce, never message data. Native review is
FLAG_SECURE, save-disabled, excludes content capture and refuses autofill descendant export,
including explicit include-unimportant requests. Accessibility remains available for human review.

All tests use synthetic values and intercepted calls, with no actual composer or user transmission.
UX44 remains documentation-only; TTS/voice and remaining F1/F2/F3 stay open, UX34 stays gated,
and UX43 remains last. Final test/build/audit and signed delivery receipts will follow only once verified.

## Retained exploratory results

The initial SDK run exposed an old catalog-count assertion and a new cross-realm object comparison;
fixed test expectations without weakening field/receipt checks. Corrected SDK passed 20 cases.
The initial shared-core run failed one newly written preview fixture because it supplied an adapter
that intentionally throws rather than the existing simulatedEffects adapter; production policy was
unchanged. The fixture was corrected and a fresh run started. Initial evidence is retained locally,
not published as host reports. Preliminary results do not substitute for final frozen-source gates.

Corrected shared core passed 485 cases without failures/errors/skips. The accompanying runtime
run exposed one remaining old 23-method assertion after expansion to 25 methods; corrected to 25
while retaining all original matrix rows and adding both editor rows. Final aggregate will be fresh.

Checkpoint `5bdc9d5` is verified on the remote with normal history. Native focused validation
compiled production successfully; its first test compile exposed two fixture annotations on
SDK-hidden ViewStructure methods, corrected without production changes. The next focused run
executed 217 cases: 213 passed and four new cross-SDK privacy cases failed only because the
unattached Robolectric accessibility node omitted contentDescription. Those fixtures retain the
full node.text accessibility assertion and now check the initialized view description directly.
No failed run is a final gate. The final 1,074 app inputs are frozen for fresh lint, six-suite
aggregate, SDK and three APK builds. Independent source review finds no production blocker so far.

## Final frozen-source host receipt

Implementation checkpoint `5bdc9d5`; fixture correction `6980fc0`; app tree
`8a16b5d2ba3bfff9f0c2271a6e987010c6c8f7c3`. Root README publication `6ce216e` is
documentation-only. All 1,074 app inputs were identical for final lint, aggregate and builds.

Fresh aggregate: Full 3,329; Play 2,947; runtime 72+72; shared core 485+485, total 7,390,
zero failures/errors/skips. SDK 20/20. All 17 test JVMs have matching offline-guard evidence;
16 external HTTP(S) attempts were blocked, eight per host flavor, matching the previous baseline,
zero in core/runtime. No individual test attribution. Fresh lint preserves the exact diagnostic
multisets 313/300/3/2, with no additions/removals or new suppressions. Independent source,
history, v63–v72 test preservation, gate freshness and actual three-APK audit passed.

Actual unsigned APKs, version 73 / `1.2.66-FACTORY-EDITORS`:
- FullDebug: 35,153,288 bytes; SHA-256 `2161ed8dae3fa57255b0b5bc3f064edd12d180b57510f55e63d8cd0a36b03241`.
- PlayDebug: 33,541,582 bytes; SHA-256 `73c26063d23f7163656329cbe59a7ba48c2893d0f2853c91a688ef58cfe34ae0`.
- FullRelease: 27,449,609 bytes; SHA-256 `2156f046de30e46af955c6bfcd53cf9e6fb36792d4c66daf9fbb35c59ef9e5c9`.

The first binary audit stopped on generated-class name shifts. Its failure evidence is retained.
A strict symbolic comparison verified the native-review anonymous classes and all 39 release
IdentityActivity lambdas, including every parent field/method/callsite after explicit simultaneous
mapping. The full audit was repeated; no production/build change or blanket equivalence allowance.

Audit checks actual ZIP/DEX/manifests/resources, unchanged permissions/native assets, and embedded
permissionless runtime templates. No real composer, contact lookup, email/SMS sending, installation
or user-data transmission was executed. Physical Binder/UI behavior, update/data preservation,
recipient behavior and actual external-task closure remain unverified. Signature and native
delivery receipts are recorded separately after confirmation.

## Signed native delivery

Native attachment of `Jarvys-Factory-editors-full-v73-arm64-test.apk` was accepted on
10 October 2026 at 12:39:16 UTC. Size: 19,252,795 bytes; SHA-256
`2d2ca3949e1b790e62f50378cf609c3c1507fcb0616d2ee50e638477ae8cee5b`.
The existing approved D7 debug signer verifies with APK v2/v3; CRC and 16 KiB ZIP alignment pass.
The manifest and all 239 retained unsigned-source entries are byte-identical; only nine non-ARM64
native libraries are omitted, with signature metadata added. Package remains `com.jarvys.agent`,
version 73 / `1.2.66-FACTORY-EDITORS`, ARM64, minimum API 24. This test key does not update an
older A6-signed installation. ZIP alignment does not certify 16 KiB ELF page compatibility.
Accepted attachment does not prove download, installation, physical editor behavior or delivery
of any email/SMS. No credentials, private signing keys, APK binaries or host reports are published
in this source-repository receipt. Broader Factory completion and physical acceptance remain open.
