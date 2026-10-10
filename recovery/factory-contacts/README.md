# UX42 F1 v74: one human-selected contact datum

Version 74 / `1.2.67-FACTORY-CONTACTS` passed host validation and independent release audit.
The signed test APK attachment was accepted; physical acceptance remains unverified.
Calendar remains a separate pending slice.

A `contacts` capability exposes `contacts.pick({kind:"phone"|"email"})` with native human
picker launch and a second complete-value approval. Only one canonical selected item row is
queried; no address-book query, enrichment or caller-supplied URI is allowed. Android access
may use a temporary grant or existing host READ_CONTACTS, but permission alone never grants
selection authority. This explicit design revision avoids rejecting normal AOSP behavior that
omits redundant URI grants when general permission already exists. No new permission is added
or requested. Generated APKs remain permissionless and return only `{kind,value}`.

Separate authenticated host/source control, durable consumption/recovery, lifecycle revocation,
provider/picker identity checks, bounded accepted values and privacy-protected native review
are described in [APK_FACTORY](../../app/APK_FACTORY.md#single-contact-datum-selection-ux42-f1-v74).
All tests are synthetic; no user contacts, real picker, calls, calendar writes or external
transmissions are performed. Provider cancellation is best effort; a blocked worker retains
admission and automation protection. Accepted-value bounds do not bound provider allocations.

Independent design review approved the exact-row access model and identified lifecycle and
cursor-cancellation cases incorporated before final gates. Verified source, tests, lint, three-APK
and delivery receipts are recorded below. Physical
compatibility/installation remain unverified. TTS/voice/remaining F1/F2/F3, UX34 gates,
UX44 documentation-only defaults and UX43-last are preserved.

## Historical checks and corrections

The following entries record intermediate states, not the final status above.

Implementation checkpoint `d5d759f` is verified on the remote with normal history. First native
production compile passed; SDK 22/22 passed. Initial focused host run executed 209 tests and failed in four fixture
cases: three updated-system-provider flag variants and one expanded capability matrix missing
its contacts broker binding. Fixtures were corrected without weakening production. The initial
run is retained as failed, not a final pass; new activity/startup and blocked-cursor regressions
were added afterward. Corrected focused validation and final frozen-source gates remain pending.

Corrected focused gate passed 243 host tests, including 129 new contacts cases, and 577 shared-core
tests. The first complete aggregate ran 3,458 Full cases and failed one inherited preview assertion
that still expected 28 observations instead of 29. Only that count and the explicit contacts
preview-denial/unavailability assertion were corrected; all original cases remain. Independent
review permits reusing the exact pre-correction lint report because the sole app delta is this
assertion-only fixture. Original lint source freeze/timestamps are retained; equal final lint/test
input freezes are not claimed. A fresh complete six-suite aggregate and three builds will follow.

The second aggregate passed all 3,458 Full cases but failed two unchanged Play Compose cases:
concurrent measure/layout and a wrong-thread view update. Their default unconfined Compose test
dispatcher permitted IO completion to resume layout inline, matching an already corrected fixture
pattern elsewhere in this repository. Only the two rules now use `StandardTestDispatcher` with
its required opt-in/imports; every test body and assertion is unchanged. Both failed runs are
retained. Independent review approved this bounded test-only lint reuse; focused checks in both
flavors and a fresh complete aggregate remain required before release.

The first dispatcher-only focused Memory run exposed inadequate fixture readiness and ended
with missing-node failures plus JVM OOM; its evidence is retained. Compose's unbounded async
scroll loop was identified as a plausible OOM path, not proven by an OOM stack. Memory now uses
the repository's finite measured-scroll pattern with real scroll actions, test-clock settlement,
progress/full-visibility assertions, and destination readiness. All 20 cases, all 66 original
assertion calls and all three system-back actions remain. Independent review approved this
fixture-only adaptation. Isolated focused checks passed Memory 20/20 in each flavor and Workspace
2/2 in each flavor. Fresh aggregate, builds and final independent release audit remain pending.

## Final host validation

Source commit `1076189`, app tree `a471df8334c17750e144a85326c372e81e1a0561`,
1,087 frozen app inputs. The fresh complete aggregate passed 7,832 cases:
3,458 Full, 3,076 Play, runtime 72/72 and shared core 577/577. No failures, errors or skips.
SDK 22/22 passed. All 17 final test JVMs have verified guard installation and shutdown reports;
16 HTTP(S) attempts were blocked (8 per host flavor, none in runtime/core), without per-test attribution.
The failed exploratory OOM JVM has installation but no shutdown report: its blocked-attempt count
is unknown and it is not passing release evidence.

Exact lint diagnostic multisets remain 313/300/3/2, with no added/removed diagnostics or suppression
changes. Original lint predates three independently reviewed test-only fixture corrections;
production, build, resource and manifest inputs are identical. The final aggregate and all three
APK builds use the final frozen source; original lint timestamps and its different test-input
freeze remain explicit.

Independent final source, gate, history, v63–v73 case-preservation and all three actual APK audits
passed. Release contact bytecode expansion follows exact invoked compiler-outline signatures,
excluding an unrelated uncalled WebView overload while checking every reachable outline method.
The initial audit stop is recorded as an outline-expansion correction, not a product change.

| Unsigned APK | Bytes | SHA-256 |
| --- | ---: | --- |
| FullDebug | 35,188,832 | `4cd164a652c58df95df2bf0840be35c2eb69b47ec8723e47bf39ce2ef6888348` |
| PlayDebug | 33,577,218 | `331e1e65ad6d5767bfca2dd21f5a75564bb0b9aff8e18670fea4c128b37fda26` |
| FullRelease | 27,478,469 | `27733cd26e8538c409c83818be1c1a1d9bd94345b5d76f41f9d812e0df303da2` |

Version 74 / `1.2.67-FACTORY-CONTACTS`. Signed test APK attachment was accepted on 2026-10-10 at 14:20:12 UTC.
No real contacts, calendar writes, user-data transmission or device installation occurred in these
checks. Synthetic tests and binary inspection do not establish physical Android acceptance.

## Signed native delivery

`Jarvys-Factory-contacts-full-v74-arm64-test.apk`: 19,277,371 bytes,
SHA-256 `003c778571e5654c436339f63c349897dbcc9aeeaa5beaad237eda95c0603163`.
The existing D7 test signer verified with v2/v3; ZIP CRC and 16 KiB alignment passed.
All 239 retained source entries are byte-identical, including manifest, DEX, resources and
embedded templates. Only nine non-ARM64 native-library entries were omitted. The signed APK
is ARM64-only and remains a test build, not a production distribution certificate.

Native attachment acceptance at 14:20:12 UTC establishes accepted delivery only, not download,
installation or physical behavior. No real contacts were selected and no calendar write or
user-data transmission was performed during validation. Calendar, other F1 work, strict TTS/voice,
F2/F3 and the wider Factory completion remain pending.
