# Factory selected SQLite data backup v83: staged host evidence

Version 83 / `1.2.76-FACTORY-DATA-BACKUP` adds the bounded selected-table backup slice.
The catalog retains 20 capabilities, with 44 methods; SDK 2 and schema/protocol 1 remain.
This does not close all backup variants or all Factory work.

## Bounded contract


Generated applications opt in through an explicit table allowlist; omitted configuration defaults
to an empty list. Both existing
`database` and `documents` capabilities are required; no new Android permission or capability
is introduced. Export/import selects at most eight allowed SQLite tables, with a canonical,
strictly validated, versioned plaintext archive limited to 1 MiB. Package/certificate/schema
labels establish compatibility, not cryptographic provenance of imported data.

The existing document-handle boundary supplies imported bytes. The pinned Jarvys native host
independently verifies installed source and signed receipt policy, displays all outgoing/incoming values and replacement target row counts/deletion impact,
requires explicit human acknowledgement and discloses potential private content and provider
cloud synchronization. Arbitrary strings cannot be guaranteed secret-free. The default-empty
allowlist is not a secret detector; each request supplies its exact nonempty table set. Signing-identity backup is a separate feature.

Export uses a native document picker only after review; JavaScript receives neither paths nor
URIs. Provider close/request success does not establish durable storage or cloud delivery.
Import supports explicit `insertOnly` and destructive `replaceSelectedTables` modes. Each
selected-table snapshot and each restore uses one SQLite transaction. Restore rechecks the
reviewed schema and selected-table preimage within its transaction; unrelated tables remain
untouched. No transaction remains open across human review or provider I/O. Validation,
conflict and known precommit failure roll back together; uncertain commit is not success.
Only selected SQLite rows are restored. Android grants, signing identity, Keystore configuration
and other storage systems are outside this API. Secret material is unsupported: arbitrary strings
may contain secrets that this feature cannot identify or protect automatically.

The five-minute operation, bounded transfers, cancellation, same-operation callback proof,
scoped host review handoff and durable recovery admission retain fail-closed lifecycle rules.
Provider cleanup that blocks retains its admission; a timeout is not a forced-stop guarantee.
Cancellation or reply loss during or after commit may mean data committed. No automatic
retry or replay follows process death or uncertain completion.

The native review calls the AndroidX state-saving superclass and clears its output in finally,
including failure paths. API 26/28/34 regressions prove provider invocation, lifecycle transition,
cleared private values/consent/URI, serialization and rejected non-null-state recreation.
This is not a claim that framework post-save bookkeeping is always an empty Bundle.
Full API details: [APK_FACTORY.md](../../app/APK_FACTORY.md#selected-sqlite-data-backup-v83).

## Source and staged test boundary

Final source `ec1523a6e51f4517de71096c3d6cbe7f6903ac8a`; app tree
`9c85ebeb7e8ed18b060c9d14f53a4ae530910042`, with 1,163 tracked app inputs.
The argument-only validation perimeter was independently reviewed before updating proof pins;
all 24 pins match. No new permission/capability or broad lifecycle exception was introduced.

The original six-task v83 run contains 9,272 cases and remains recorded as failed: two stale
preview catalog expectations and one Play memory UI timeout. Entire affected classes in BOTH
flavors were replaced by 70 fresh passing cases, preserving all original assertions and adding
catalog-derived coverage. The separate unchanged memory diagnostic passed 40 cases; it does
not prove a historical flake and is not added to the accepted total.

Fresh lint then found a missing superclass state-save call in the new native review Activity.
The reviewed production correction and two tests were checked on API 26/28/34. All four complete
host-backup classes were rerun in both flavors: 66 passing cases replace all 54 prior cases.

The independently reconciled total is 9,284 passing cases, zero failures/errors/skips:
Full 3,568; Play 3,186; generated runtime 89+89; shared core 1,176+1,176.
This is impact-based staged evidence, NOT a repeated passing full aggregate. The original failed
reports remain intact. All ten exhaustive 2^20 matrix instances remain passing and source-bound.
Fresh SDK tests: 45/45.

Reuse is supported by all frozen source hashes, actual ordered worker classpaths, 321 resolved
input snapshots and AGP resource bindings. The first correction changed only two test sources.
The later production correction changed only one Activity source and its test: two production
JARs and two test-output directories changed. Every JAR entry was compared; the five changed
entries per flavor originate from the Activity file. The Activity executable difference is confined to
onSaveInstanceState; the other four generated classes have identical normalized executable listings.
The snapshots are post-execution byte evidence, not JVM-load attestation or a complete toolchain inventory.

All 22 worker records spanning the original run and both accepted replacement stages have
paired offline-guard install/shutdown evidence. Sixteen intercepted Java HTTP(S) attempts occur
in the original run, with no URL/test/cause attribution; neither replacement run recorded one.
The two unchanged-memory diagnostic workers are separate. No zero-egress claim.

Unit impact acceptance SHA-256:
`12348e343167f006c1911274529ddb608f3a5d26f8ae3b071491ea930d809cfa`.
Combined source/test/SDK/build execution acceptance:
`065af076b38a3d4f24b8b80b550188bf57381dceccc0566b6b737c7517eb3e9d`.

## Lint and retained attempts

Fresh lint retains 321/311/5/4 diagnostics, including existing legacy findings and the unchanged
abortOnError=false configuration. Full, Play and runtime reports are byte-identical to v82.
Core differs only in the independently pinned RequiresFeature location, line 310 to 317:
the diagnostic and complete enclosing initializeWebView method are otherwise unchanged.
No new warning, suppression or broad location normalization is accepted.

The original 66-file metadata byte/domain inventory, exact two prior marker records and two empty
directories remain unchanged. The successful --stacktrace packaging retry logged the existing
runtime lint report task rerunning because its printStackTrace input changed. One additional cache
directory containing only the exact 30-byte version marker was observed during that run; this
is not OS-level writer attribution. A separate read-only verifier approves only
that addition; the older verifier failure is retained. Metadata acceptance SHA-256:
`82bc148abf9c9fdd2ea6c092dee9ede5f50eeaf553595ccbf348b16a3574b861`.

The first FullRelease packaging attempt failed without an underlying cause in its log; the
same-source three-APK retry succeeded with stacktrace enabled. No source, signing, dependency,
cache cleanup or guard change was used. The original cause remains unknown. Earlier compile/
fixture failures, interrupted runs and wrong-directory launches are retained, not passing evidence.
Build/lint-init provenance is separate from the unit receipt; the build init matches the preserved
v82 hash. Historical lint-init byte identity is not claimed solely from equal invocation arguments.

## APK and delivery

The exact three unsigned APKs and their actual common embedded template passed independent
manifest/resource/asset/native/profile and complete strict DEX/class/method/metadata review.
FullDebug has 28,533 classes (28,314 unchanged); PlayDebug 26,170 (25,954 unchanged);
FullRelease 27,128 (26,899 unchanged). Each host variant adds 103 classes and removes none.
The actual embedded template has 3,714 classes. All changed/added classes, methods and metadata
are accounted for by concrete source scopes and bounded compiler proofs, including release
switch alignment and the unchanged DatabaseRequest optimizer relocation. No blanket compiler
normalization or unmatched package drift is accepted. The new native review Activity and
localized resources are explicitly reviewed; signing and delivery remain a separate gate.

Unsigned three-APK approval SHA-256:
`22594c697984f17a437bcbe6b3b83b5dafe58c403bd62fa6470874fe59cb5b6a`.

Native attachment accepted October 11, 2026 at 11:39:23 UTC:
`Jarvys-Factory-data-backup-full-v83-arm64-test.apk`, 19,459,238 bytes, SHA-256
`2e945efb339038703ac3070c64745903a8f3cd8a4e4663e8c2e542e46ae6b4bd`.
Independent verification checks the existing D7 test identity, v2/v3 signatures, ZIP CRC and
16 KiB ZIP alignment. All 255 retained payload entries exactly match the approved unsigned
release; nine non-ARM64 libraries are omitted and three signature entries are added. The actual
persisted Library bytes were independently retrieved and exactly match the signed artifact.
Signed/Library receipt SHA-256:
`ea3f5fe9e8717da350a93cd02f25eb75e16610facdb164e56a64a4ed021c6bf8`.
Native acceptance does not establish user download, installation/update or physical recovery.
ZIP alignment does not establish ELF 16 KiB page compatibility.

Approved unsigned FullRelease: 27,665,452 bytes, SHA-256
`300d3755ce82e56723145a9ea83ac8e680177ad2e947c782bfc0a8408a54548f`.

## Acceptance still open

Physical Android Binder/picker/provider behavior, installation/update, data retention after device
failure, cloud/provider durability and real user-data recovery are not established by host tests.
No user database was read or transmitted by the synthetic tests. Preferences, broader merge/
migration, TTS/voice, remaining F1/F2/F3 and the real-traffic network gate remain open.
UX34 gates, UX44 documentary defaults and UX43-last retain their prior boundaries.
