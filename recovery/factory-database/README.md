# UX42 F1 v76: private typed SQLite

Version 76 / `1.2.69-FACTORY-DATABASE`: final host tests, exact lint, three APK builds and
independent source/binary audit passed. Signed native attachment accepted; exact Library bytes
independently verified. This is not
physical Android acceptance or completion of Factory.

A standalone zero-permission `database` capability supplies typed CRUD, equality/ID queries,
bounded atomic batches and adjacent-version create-table/add-nullable-column migrations. Values
are bound; identifiers are closed native grammar. One installed app owns its fixed private
no-backup SQLite database. No host broker, per-query native review, external transmission,
arbitrary SQL/path, ATTACH, extension loading, index/drop/rename step or database export API.
Preview uses isolated in-memory SQLite and loses it on close/reset/pause. Installed close/reopen
retains data; no encryption, automatic backup, secure erasure or physical durability is claimed.

Capability, installed identity, trusted main-frame origin and active session remain checked.
Cancellation invalidates queued work; precommit failures roll back schema/version/data together.
A commit that crossed its boundary is not undone; lost replies require inspection, never blind
replay. Corrupt data/metadata is preserved rather than silently reset. The 16 MiB main-database
cap is not a total disk/RAM ceiling: journal/temp files, caches and cursors consume extra space.
Exact methods, types, limits and pagination semantics are in [APK_FACTORY](../../app/APK_FACTORY.md#private-structured-database-ux42-f1-v76).

## Retained exploratory and failed attempts

Design/source review corrected preview foreground authority, enlarged-row migration rollback,
missing-metadata preservation and persisted-value type checks. Initial database-focused execution
ran 66 cases with 35 failures because Android-owned in-memory metadata was mistaken for an
existing user schema. The explicit Android metadata exception then passed all 66. A wrong-working-
directory compile invocation also failed without running tests. Later final database-focused 70
cases passed, including actual page/WAL configuration, oversized-file preservation and exact ID
lookup. Intermediate complete core678/runtime72 and SDK27 passed; these are not the final aggregate.

Both-flavor skill/preview/manifest preflight passed 148+148. The 16,380-byte skill stays strictly
below 16 KiB while preserving prior contracts and tested phrases. All 17 capability bits are
actually covered by 131,072 manifest profiles; the previously disclosed v74 coverage gap remains
historical, not retroactively repaired.

The first full aggregate stopped after Full: 3,498 cases with one inherited
MainAgentComposerTest.spanishLargeLight concurrency failure. Markdown parsing resumed composition
from a background worker during applyChanges. A test-only StandardTestDispatcher correction
exposed asynchronous Markdown readiness: its first focused run had 33 Full cases with 10 failures.
A bounded condition wait now precedes the unchanged original visibility assertion. Every prior
assertion, interaction and capture remains; production UI did not change. The final related
fixtures passed 33 Full+33 Play. Both failed runs are retained. Fresh lint, the entire aggregate
and all APK builds were repeated after the reviewed fixture correction; no lint reuse waiver.

## Final frozen-source host receipt

Implementation checkpoint `1606a9e`; reviewed fixture checkpoint `14d24f8`.
App tree `9dc77a3bc380bf2e92466f5de3c818b8dadcdf1e`. All 1,099 app inputs are identical across
final lint, aggregate, build and after-build freezes.

Fresh six-task aggregate: Full 3,498; Play 3,116; runtime 72+72; shared core 682+682.
Total 8,122, zero failures/errors/skips. SDK27/27. All 17 test JVMs retain complete paired offline-
guard installation/shutdown evidence. The guard blocked 16 HTTP(S) attempts, eight per host flavor
and zero in runtime/core; these are per-JVM totals, not attribution to individual tests.
Fresh lint preserves the exact inherited diagnostic multisets 313/300/3/2, without additions,
removals, suppression or waiver.

Independent final review passed source, historical v63–v75 test preservation with existing
recorded name-migration qualifications, normal Git history and all failed-attempt evidence,
fresh tests/guards/lint, and three actual APKs plus embedded runtime templates. Permissions,
manifest surface, resources and dependencies are unchanged except host version metadata. Actual
DEX checks confirm native SQLite/typed binding/cancellation wiring and the six-method SDK.

Actual unsigned APKs, version 76 / `1.2.69-FACTORY-DATABASE`:
- app-full-debug-unsigned.apk: 35,225,828 bytes; SHA-256 `c77d03cd1cef7ce7f2165796220cff7ef1df151cdf18ba63f3e7ec3097912c2e`.
- app-play-debug-unsigned.apk: 33,614,354 bytes; SHA-256 `878691ac3c02d26bbe4989919fdd3e51eefbea4a3491e94efe9ef89b504f0e6f`.
- app-full-release-unsigned.apk: 27,513,409 bytes; SHA-256 `107ee8b2a3016c9295f423bcd9aa8f0e706ac765f117cc4180c1e5e615b57e0b`.

No real user data migration, device files, external transmission, installation, process-death,
power-loss or data-preserving Android update was performed. Synthetic host tests and bytecode
inspection do not establish physical device/OEM/WebView persistence or compatibility. Signing
and accepted attachment do not prove download, installation or retained data. Remaining F1,
strict TTS/voice, sensors/biometrics, F2/F3, UX34 gates, UX44 documentation-only future defaults
and UX43-last are preserved.


## Signed native delivery receipt

Native ARM64 test APK attachment accepted on 2026-10-10 at 16:53:20.385629 UTC.
Signed size: 19,310,139 bytes. SHA-256:
`af2d78fa1890c8805f6f347374a5c8677d5bfd4d6971bb4fbe44b6cce9d914c1`.

Independent review retrieved the exact delivered Library artifact afresh and matched it to the
signer's bytes. APK v2/v3 signatures, CRC and 16 KiB ZIP alignment pass. D7 test certificate SHA-1:
`D7:C0:1F:59:79:78:32:3E:32:CA:AF:22:E9:F6:00:9A:B7:2F:32:B8`.
Package `com.jarvys.agent`, version 76 / `1.2.69-FACTORY-DATABASE`, ARM64-only. All 239 retained
entries are byte-identical to the independently audited unsigned release APK; exactly nine
non-ARM64 native libraries were omitted and only signing entries added. ZIP alignment does not
establish ELF 16 KiB page compatibility. Attachment acceptance does not establish download,
installation, physical database persistence or data-preserving upgrade acceptance.
